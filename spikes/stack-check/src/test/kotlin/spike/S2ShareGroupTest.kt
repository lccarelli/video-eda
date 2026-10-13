package spike

import org.apache.kafka.clients.consumer.AcknowledgeType
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaShareConsumer
import org.apache.kafka.clients.consumer.ShareConsumer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.kafka.config.ShareKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultShareConsumerFactory
import org.springframework.kafka.listener.AcknowledgingShareConsumerAwareMessageListener
import org.springframework.kafka.listener.ContainerProperties.ShareAckMode
import org.springframework.kafka.listener.ShareKafkaMessageListenerContainer
import org.springframework.kafka.support.ShareAcknowledgment
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

data class Delivery(val member: Int, val value: String, val deliveryCount: Short?, val atMs: Long)

class S2ShareGroupTest {
    private val running = CopyOnWriteArrayList<ShareKafkaMessageListenerContainer<String, String>>()
    private val deliveries = CopyOnWriteArrayList<Delivery>()
    private val t0 = System.currentTimeMillis()
    private val nullAcks = AtomicInteger()

    @AfterEach fun stopAll() { running.forEach { runCatching { it.stop() } }; running.clear() }

    private fun consumerFactory() = DefaultShareConsumerFactory<String, String>(
        mapOf(
            "bootstrap.servers" to Broker.bootstrapServers,
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
        )
    )

    private fun startGroup(
        topic: String, group: String, members: Int = 3, mode: ShareAckMode = ShareAckMode.MANUAL,
        handler: (member: Int, r: ConsumerRecord<String, String>, ack: ShareAcknowledgment?) -> Unit,
    ) {
        repeat(members) { i ->
            val factory = ShareKafkaListenerContainerFactory(consumerFactory())
            factory.containerProperties.shareAckMode = mode
            val c = factory.createContainer(topic)
            c.containerProperties.shareAckMode = mode
            c.containerProperties.setGroupId(group)
            c.containerProperties.setMessageListener(
                AcknowledgingShareConsumerAwareMessageListener<String, String> { r, ack, _ ->
                    deliveries.add(Delivery(i, r.value(), r.deliveryCount().map { it }.orElse(null), System.currentTimeMillis() - t0))
                    if (mode == ShareAckMode.MANUAL && ack == null) { nullAcks.incrementAndGet(); error("ShareAcknowledgment nulo en modo MANUAL") }
                    handler(i, r, ack)
                }
            )
            c.beanName = "s2-$group-$i"
            c.start()
            running.add(c)
        }
        assertTrue(
            awaitUntil { runCatching { Broker.withAdmin { a -> a.describeShareGroups(listOf(group)).all().get()[group]!!.members().size == members } }.getOrDefault(false) },
            "los $members miembros de $group se unieron",
        )
    }

    @AfterEach fun noNullAcks() { assertEquals(0, nullAcks.get(), "hubo acks nulos: el modo MANUAL no se aplicó") }

    private fun count(v: String) = deliveries.count { it.value == v }
    private fun show(title: String) = println("S2 $title: " + deliveries.joinToString { "${it.value}@m${it.member}#dc=${it.deliveryCount}+${it.atMs}ms" })

    @Test
    fun `S2-a veinte mensajes con tres consumers se procesan una vez cada uno`() {
        Broker.createTopic("s2.a"); Broker.setGroupConfig("s2-a", "share.auto.offset.reset" to "earliest")
        startGroup("s2.a", "s2-a") { _, _, ack -> ack!!.acknowledge() }
        Broker.produceRecords("s2.a", (1..20).map { "k$it" to "m$it" })
        assertTrue(awaitUntil { deliveries.map { it.value }.toSet().size == 20 }, "20 distintos")
        Thread.sleep(3_000)
        val perMember = deliveries.groupingBy { it.member }.eachCount()
        println("S2-a reparto por miembro: $perMember; total entregas=${deliveries.size}")
        assertEquals(20, deliveries.size, "ninguna entrega duplicada")
    }

    @Test
    fun `S2-b ACCEPT RELEASE y REJECT tienen efectos distintos`() {
        Broker.createTopic("s2.b"); Broker.setGroupConfig("s2-b", "share.auto.offset.reset" to "earliest")
        val seen = ConcurrentHashMap<String, AtomicInteger>()
        startGroup("s2.b", "s2-b") { _, r, ack ->
            val n = seen.computeIfAbsent(r.value()) { AtomicInteger() }.incrementAndGet()
            when (r.value()) {
                "accept" -> ack!!.acknowledge()
                "release" -> if (n == 1) ack!!.release() else ack!!.acknowledge()
                "reject" -> ack!!.reject()
            }
        }
        Broker.produceRecords("s2.b", listOf("k1" to "accept", "k2" to "release", "k3" to "reject"))
        val redelivered = awaitUntil(20_000) { count("release") >= 2 }
        Thread.sleep(8_000)
        show("b")
        assertTrue(redelivered, "release se re-entrega")
        assertEquals(1, count("accept"), "ACCEPT: una entrega")
        assertEquals(2, count("release"), "RELEASE: se re-entrega una vez y luego se acepta")
        assertEquals(1, count("reject"), "REJECT: no se re-entrega")
    }

    @Test
    fun `S2-c1 lock expirado con 3 particiones y 3 consumers`() = lockExpiry("s2.c1", "s2-c1", partitions = 3)

    @Test
    fun `S2-c2 lock expirado con 1 particion y 3 consumers`() = lockExpiry("s2.c2", "s2-c2", partitions = 1)

    private fun lockExpiry(topic: String, group: String, partitions: Int) {
        Broker.createTopic(topic, partitions)
        Broker.setGroupConfig(group, "share.auto.offset.reset" to "earliest", "share.record.lock.duration.ms" to "2000")
        awaitUntil(10_000) { Broker.describeGroupConfig(group)["share.record.lock.duration.ms"] == "2000" }
        val cfg = Broker.describeGroupConfig(group)
        println("S2-c [$topic] describeConfigs: share.record.lock.duration.ms=${cfg["share.record.lock.duration.ms"]} share.auto.offset.reset=${cfg["share.auto.offset.reset"]}")
        assertEquals("2000", cfg["share.record.lock.duration.ms"])
        var lateAck: String = "no intentado"
        startGroup(topic, group) { _, r, ack ->
            if (count(r.value()) == 1) {
                Thread.sleep(6_000)
                lateAck = runCatching { ack!!.acknowledge() }.fold({ "acknowledge() sin excepción" }, { "excepción: $it" })
            } else ack!!.acknowledge()
        }
        Broker.produceRecords(topic, listOf("k1" to "lento"))
        val redelivered = awaitUntil(25_000) { count("lento") >= 2 }
        Thread.sleep(8_000)
        show("c [$topic]"); println("S2-c [$topic] ack tardío del primer consumer: $lateAck")
        assertTrue(redelivered, "re-entrega tras expirar el lock")
        println("S2-c [$topic] primer miembro=${deliveries[0].member}, segundo miembro=${deliveries[1].member}")
    }

    @Test
    fun `S2-d1 renew() de Spring dentro del listener NO evita la re-entrega (refutacion)`() {
        Broker.createTopic("s2.d1")
        Broker.setGroupConfig("s2-d1", "share.auto.offset.reset" to "earliest", "share.record.lock.duration.ms" to "3000")
        startGroup("s2.d1", "s2-d1") { _, _, ack ->
            repeat(5) {
                Thread.sleep(1_000)
                val r = runCatching { ack!!.renew() }
                println("S2-d1 renew #${it + 1} a los ${System.currentTimeMillis() - t0}ms -> ${r.fold({ "ok" }, { e -> "EXCEPCIÓN $e" })}")
            }
            val a = runCatching { ack!!.acknowledge() }
            println("S2-d1 acknowledge final a los ${System.currentTimeMillis() - t0}ms -> ${a.fold({ "ok" }, { e -> "EXCEPCIÓN $e" })}")
        }
        Broker.produceRecords("s2.d1", listOf("k1" to "lento"))
        assertTrue(awaitUntil(20_000) { count("lento") >= 1 })
        Thread.sleep(10_000)
        show("d1")

        assertTrue(count("lento") >= 2, "el lock expiró igual y hubo re-entrega")
    }

    @Test
    fun `S2-d2 RENEW con el cliente de Kafka directo mantiene el lock (RENEW, commitSync y poll)`() {
        Broker.createTopic("s2.d2", 1)
        Broker.setGroupConfig("s2-d2", "share.auto.offset.reset" to "earliest", "share.record.lock.duration.ms" to "3000")
        awaitUntil(10_000) { Broker.describeGroupConfig("s2-d2")["share.record.lock.duration.ms"] == "3000" }
        val props = mapOf<String, Any>(
            "bootstrap.servers" to Broker.bootstrapServers, "group.id" to "s2-d2",
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "share.acknowledgement.mode" to "explicit",
        )
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val log = CopyOnWriteArrayList<String>()
        val receivers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val accepted = AtomicInteger()
        fun now() = System.currentTimeMillis() - t0

        fun worker(name: String) = Thread {
            KafkaShareConsumer<String, String>(props).use { c ->
                c.subscribe(listOf("s2.d2"))
                var current: ConsumerRecord<String, String>? = null
                while (!stop.get()) {
                    if (current == null) {
                        current = c.poll(Duration.ofMillis(300)).firstOrNull() ?: continue
                        receivers.add(name)
                        log.add("$name recibió ${current.value()} a los ${now()}ms dc=${current.deliveryCount()} lockMs=${c.acquisitionLockTimeoutMs()}")
                        val started = System.currentTimeMillis()
                        repeat(5) { n ->
                            Thread.sleep(1_000)
                            c.acknowledge(current!!, AcknowledgeType.RENEW)
                            val res = c.commitSync()
                            val again = c.poll(Duration.ofMillis(1_000)).firstOrNull()
                            log.add("$name renew #${n + 1} a los ${now()}ms commitSync=$res; poll siguiente -> ${again?.value()} dc=${again?.deliveryCount()}")
                            if (again != null) current = again
                        }
                        c.acknowledge(current!!, AcknowledgeType.ACCEPT); c.commitSync(); accepted.incrementAndGet()
                        log.add("$name ACCEPT a los ${now()}ms tras ${System.currentTimeMillis() - started}ms de trabajo")
                        current = null
                    }
                }
            }
        }
        val ts = listOf(worker("A"), worker("B")).onEach { it.start() }
        Thread.sleep(3_000)
        Broker.produceRecords("s2.d2", listOf("k1" to "lento"))
        awaitUntil(30_000) { accepted.get() >= 1 }
        Thread.sleep(6_000); stop.set(true); ts.forEach { it.join() }
        log.forEach { println("S2-d2 $it") }
        assertEquals(1, accepted.get(), "se aceptó una sola vez tras 5 s de trabajo con lock de 3 s")
        assertEquals(1, receivers.size, "un solo worker recibió el mensaje: el lock se mantuvo; receptores=$receivers")
    }

    @Test
    fun `S2-e deliveryCount se expone y crece con cada re-entrega`() {
        Broker.createTopic("s2.e"); Broker.setGroupConfig("s2-e", "share.auto.offset.reset" to "earliest")
        startGroup("s2.e", "s2-e") { _, _, ack -> if (deliveries.size < 3) ack!!.release() else ack!!.acknowledge() }
        Broker.produceRecords("s2.e", listOf("k1" to "poison"))
        val ok = awaitUntil(25_000) { count("poison") >= 3 }
        show("e")
        assertTrue(ok, "tres entregas")
        val counts = deliveries.map { it.deliveryCount }
        assertEquals(listOf<Short?>(1, 2, 3), counts.take(3), "deliveryCount por entrega: $counts")
    }

    @Test
    fun `S2-f share auto offset reset earliest recibe lo anterior y latest no`() {
        Broker.createTopic("s2.f")
        Broker.produceRecords("s2.f", listOf("k1" to "antes"))
        Broker.setGroupConfig("s2-f-earliest", "share.auto.offset.reset" to "earliest")
        val early = AtomicInteger(); val late = AtomicInteger()
        startGroup("s2.f", "s2-f-earliest", members = 1) { _, _, ack -> early.incrementAndGet(); ack!!.acknowledge() }
        startGroup("s2.f", "s2-f-latest", members = 1) { _, _, ack -> late.incrementAndGet(); ack!!.acknowledge() }
        Thread.sleep(8_000)
        println("S2-f earliest recibió=${early.get()} latest recibió=${late.get()}; default config latest=${Broker.describeGroupConfig("s2-f-latest")["share.auto.offset.reset"]}")
        assertEquals(1, early.get()); assertEquals(0, late.get())
    }
}
