package dev.videoeda.videos.infrastructure

import dev.videoeda.videos.application.Transaction
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class SpringTransaction(transactionManager: PlatformTransactionManager) : Transaction {
    private val template = TransactionTemplate(transactionManager)

    override fun <T> run(block: () -> T): T = template.execute { block() }
}
