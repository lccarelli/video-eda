package spike

import au.com.dius.pact.consumer.MessagePactBuilder
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

@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "videos-zero", providerType = ProviderType.ASYNCH, pactVersion = PactSpecVersion.V4)
class ZeroInteractionsPactTest {
    @Pact(consumer = "zero-consumer")
    fun emptyPact(builder: MessagePactBuilder): V4Pact = builder.toPact(V4Pact::class.java)

    @Test
    @PactTestFor(pactMethod = "emptyPact")
    fun `CTR-01 cero interacciones`(messages: List<V4Interaction.AsynchronousMessage>) {
        assertEquals(0, messages.size)
    }
}
