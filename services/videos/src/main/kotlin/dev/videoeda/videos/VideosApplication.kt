package dev.videoeda.videos

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class VideosApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<VideosApplication>(*args)
}
