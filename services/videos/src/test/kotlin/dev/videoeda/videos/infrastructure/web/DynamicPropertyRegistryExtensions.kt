package dev.videoeda.videos.infrastructure.web

import org.springframework.test.context.DynamicPropertyRegistry

fun DynamicPropertyRegistry.withoutProcessVideoWorker() = add("videos.worker.enabled") { "false" }
