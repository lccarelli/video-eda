package spike

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.GroupType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.config.ShareKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultShareConsumerFactory
import org.springframework.kafka.listener.AbstractMessageListenerContainer
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

const val T_CLASSIC = "s1.classic"
const val T_KIP848 = "s1.kip848"
const val T_SHARE = "s1.share"
const val G_CLASSIC = "s1-classic-group"
const val G_KIP848 = "s1-kip848-group"
const val G_SHARE = "s1-share-group"

class Inbox {
    val byType = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    fun add(type: String, key: String) { byType.computeIfAbsent(type) { CopyOnWriteArrayList() }.add(key) }
    fun keys(type: String): List<String> = byType[type] ?: emptyList()
}

@Configuration
@EnableKafka
class S1Config {
    init {
        Broker.createTopic(T_CLASSIC); Broker.createTopic(T_KIP848); Broker.createTopic(T_SHARE)
        Broker.setGroupConfig(G_SHARE, "share.auto.offset.reset" to "earliest")
    }

    private fun consumerProps(protocol: String, group: String): Map<String, Any> = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to Broker.bootstrapServers,
        ConsumerConfig.GROUP_ID_CONFIG to group,
        ConsumerConfig.GROUP_PROTOCOL_CONFIG to protocol,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to "org.apache.kafka.common.serialization.StringDeserializer",
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to "org.apache.kafka.common.serialization.StringDeserializer",
    )

    @Bean fun classicFactory(): ConcurrentKafkaListenerContainerFactory<String, String> =
        ConcurrentKafkaListenerContainerFactory<String, String>().also {
            it.setConsumerFactory(DefaultKafkaConsumerFactory(consumerProps("classic", G_CLASSIC)))
        }

    @Bean fun kip848Factory(): ConcurrentKafkaListenerContainerFactory<String, String> =
        ConcurrentKafkaListenerContainerFactory<String, String>().also {
            it.setConsumerFactory(DefaultKafkaConsumerFactory(consumerProps("consumer", G_KIP848)))
        }

    @Bean fun shareFactory(): ShareKafkaListenerContainerFactory<String, String> =
        ShareKafkaListenerContainerFactory(
            DefaultShareConsumerFactory<String, String>(
                mapOf(
                    "bootstrap.servers" to Broker.bootstrapServers,
                    "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                    "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
                )
            )
        )

    @Bean fun inbox() = Inbox()
    @Bean fun listeners(inbox: Inbox) = S1Listeners(inbox)
}

@Component
class S1Listeners(private val inbox: Inbox) {
    @KafkaListener(id = "s1-classic", topics = [T_CLASSIC], groupId = G_CLASSIC, containerFactory = "classicFactory")
    fun classic(r: ConsumerRecord<String, String>) = inbox.add("classic", r.key())

    @KafkaListener(id = "s1-kip848", topics = [T_KIP848], groupId = G_KIP848, containerFactory = "kip848Factory")
    fun kip848(r: ConsumerRecord<String, String>) = inbox.add("kip848", r.key())

    @KafkaListener(id = "s1-share", topics = [T_SHARE], groupId = G_SHARE, containerFactory = "shareFactory")
    fun share(r: ConsumerRecord<String, String>) = inbox.add("share", r.key())
}

@SpringBootTest(classes = [S1Config::class])
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class S1StackStartsTest {
    @Autowired lateinit var registry: KafkaListenerEndpointRegistry
    @Autowired lateinit var inbox: Inbox

    @Test @Order(0)
    fun `S1-00 la version de kafka-clients en el classpath es 4_3_x`() {
        val parser = listOf("org.apache.kafka.common.utils.internals.AppInfoParser", "org.apache.kafka.common.utils.AppInfoParser")
            .firstNotNullOf { runCatching { Class.forName(it) }.getOrNull() }
        val v = parser.getMethod("getVersion").invoke(null) as String
        println("S1 kafka-clients AppInfoParser.getVersion() = $v")
        assertTrue(v.startsWith(System.getenv("KAFKA_CLIENTS_EXPECTED") ?: "4.3."), "kafka-clients $v")
    }

    @Test @Order(1)
    fun `S1-Z cero mensajes - los tres consumers arrancan y reciben nada`() {
        val classic = registry.getListenerContainer("s1-classic")!!
        val kip = registry.getListenerContainer("s1-kip848")!!
        val share = registry.getListenerContainer("s1-share")!!
        assertTrue(awaitUntil { classic.isRunning && kip.isRunning && share.isRunning }, "containers corriendo")
        assertTrue(awaitUntil { (classic as AbstractMessageListenerContainer<*, *>).assignedPartitions!!.isNotEmpty() }, "classic con asignación")
        assertTrue(awaitUntil { (kip as AbstractMessageListenerContainer<*, *>).assignedPartitions!!.isNotEmpty() }, "kip848 con asignación")
        assertTrue(awaitUntil { Broker.withAdmin { a -> a.describeShareGroups(listOf(G_SHARE)).all().get()[G_SHARE]!!.members().isNotEmpty() } }, "share con miembro")

        val types = Broker.withAdmin { a -> a.listGroups().all().get().associate { it.groupId() to it.type().orElse(null) } }
        println("S1 grupos en el broker: $types")
        assertEquals(GroupType.CLASSIC, types[G_CLASSIC])
        assertEquals(GroupType.CONSUMER, types[G_KIP848])
        assertEquals(GroupType.SHARE, types[G_SHARE])

        Thread.sleep(2_000)
        assertEquals(0, inbox.keys("classic").size + inbox.keys("kip848").size + inbox.keys("share").size)
    }

    @Test @Order(2)
    fun `S1-O un mensaje llega y se procesa en cada tipo de consumer`() {
        Broker.produceRecords(T_CLASSIC, listOf("vid_paella" to "uno"))
        Broker.produceRecords(T_KIP848, listOf("vid_paella" to "uno"))
        Broker.produceRecords(T_SHARE, listOf("vid_paella" to "uno"))
        assertTrue(awaitUntil { inbox.keys("classic").size == 1 }, "classic recibió 1")
        assertTrue(awaitUntil { inbox.keys("kip848").size == 1 }, "kip848 recibió 1")
        assertTrue(awaitUntil { inbox.keys("share").size == 1 }, "share recibió 1")
    }

    @Test @Order(3)
    fun `S1-M veinte mensajes con key distinta llegan una vez cada uno`() {
        val msgs = (1..20).map { "vid_$it" to "m$it" }
        Broker.produceRecords(T_CLASSIC, msgs); Broker.produceRecords(T_KIP848, msgs); Broker.produceRecords(T_SHARE, msgs)
        for (t in listOf("classic", "kip848", "share")) {
            assertTrue(awaitUntil { inbox.keys(t).size >= 21 }, "$t recibió 21 (1 previo + 20)")
            Thread.sleep(1_000)
            assertEquals(21, inbox.keys(t).size, "$t sin duplicados")
            assertEquals(21, inbox.keys(t).toSet().size, "$t keys distintas")
        }
    }
}
