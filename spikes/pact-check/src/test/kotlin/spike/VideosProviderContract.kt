package spike

import au.com.dius.pact.provider.MessageAndMetadata
import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junitsupport.State
import org.junit.jupiter.api.BeforeEach
import videoeda.videos.VideoPublishedProducer

abstract class VideosProviderContract {
    @BeforeEach
    fun before(context: PactVerificationContext) {
        context.target = MessageTestTarget()
    }

    @State("el video ya fue procesado")
    fun videoAlreadyProcessed() {  }

    @PactVerifyProvider("VideoPublished")
    fun videoPublished(): MessageAndMetadata {
        val record = VideoPublishedProducer().videoPublished()
        return MessageAndMetadata(
            record.value.toByteArray(),
            mapOf("contentType" to "application/json", "kafka_topic" to "videos.video", "kafka_key" to record.key),
        )
    }
}
