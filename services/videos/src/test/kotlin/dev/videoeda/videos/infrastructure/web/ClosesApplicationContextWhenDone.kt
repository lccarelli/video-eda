package dev.videoeda.videos.infrastructure.web

import org.springframework.test.annotation.DirtiesContext

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
annotation class ClosesApplicationContextWhenDone
