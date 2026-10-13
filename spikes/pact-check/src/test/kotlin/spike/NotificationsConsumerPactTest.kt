package spike

import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.dsl.PactDslJsonBody
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import videoeda.notifications.NotificationsVideoPublishedHandler

@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "videos", providerType = ProviderType.ASYNCH, pactVersion = PactSpecVersion.V4)
class NotificationsConsumerPactTest {
    @Pact(consumer = "notifications")
    fun videoPublished(builder: MessagePactBuilder): V4Pact {
        val body = PactDslJsonBody()
        body.stringValue("type", "dev.videoeda.videos.video-published")
        body.integerType("sequence", 1)
        val data = body.`object`("data")
        data.stringMatcher("videoId", FeedConsumerPactTest.VID, "vid_01a13161-1277-7e7b-9435-b109a7e95989")
        data.stringMatcher("creatorId", FeedConsumerPactTest.USR, "usr_01a13161-11be-7119-8e3e-bf07f13eb833")
        data.stringType("title", "Mi primer video")
        data.closeObject()
        return builder.given("el video ya fue procesado")
            .expectsToReceive("VideoPublished")
            .withMetadata { m ->
                m.add("contentType", "application/json")
                m.add("kafka_topic", "videos.video")

                m.matchRegex("kafka_key", FeedConsumerPactTest.VID, "vid_01a13161-1277-7e7b-9435-b109a7e95989")
            }
            .withContent(body)
            .toPact(V4Pact::class.java)
    }

    @Test
    @PactTestFor(pactMethod = "videoPublished")
    fun `CTR-01 notifications procesa VideoPublished con title`(messages: List<V4Interaction.AsynchronousMessage>) {
        assertEquals(1, messages.size)
        val videoPublished = NotificationsVideoPublishedHandler().parseVideoPublished(messages[0].contents.contents.valueAsString())
        assertEquals("Mi primer video", videoPublished.title)
    }
}
