package spike

import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.dsl.PactDslJsonBody
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.annotations.Pact
import au.com.dius.pact.core.model.V4Interaction
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.messaging.Message
import au.com.dius.pact.consumer.junit5.ProviderType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import videoeda.feed.FeedVideoPublishedHandler

@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "videos", providerType = ProviderType.ASYNCH, pactVersion = PactSpecVersion.V4)
class FeedConsumerPactTest {
    companion object {
        const val VID = "^vid_[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
        const val USR = "^usr_[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    }

    @Pact(consumer = "feed")
    fun videoPublished(builder: MessagePactBuilder): V4Pact {
        val body = PactDslJsonBody()
        body.stringValue("specversion", "1.0")
        body.stringValue("type", "dev.videoeda.videos.video-published")
        body.stringMatcher("subject", VID, "vid_01a13161-1277-7e7b-9435-b109a7e95989")
        body.integerType("sequence", 1)
        val data = body.`object`("data")
        data.stringMatcher("videoId", VID, "vid_01a13161-1277-7e7b-9435-b109a7e95989")
        data.stringMatcher("creatorId", USR, "usr_01a13161-11be-7119-8e3e-bf07f13eb833")
        data.closeObject()
        return builder.given("el video ya fue procesado")
            .expectsToReceive("VideoPublished")
            .withMetadata(mapOf("contentType" to "application/json", "kafka_topic" to "videos.video"))
            .withContent(body)
            .toPact(V4Pact::class.java)
    }

    @Test
    @PactTestFor(pactMethod = "videoPublished")
    fun `CTR-01 feed procesa VideoPublished con creatorId`(messages: List<V4Interaction.AsynchronousMessage>) {
        assertEquals(1, messages.size)
        val json = messages[0].contents.contents.valueAsString()
        val videoPublished = FeedVideoPublishedHandler().parseVideoPublished(json)
        assertEquals("vid_01a13161-1277-7e7b-9435-b109a7e95989", videoPublished.videoId)
        assertEquals("usr_01a13161-11be-7119-8e3e-bf07f13eb833", videoPublished.creatorId)
    }
}
