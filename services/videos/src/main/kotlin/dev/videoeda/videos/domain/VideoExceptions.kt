package dev.videoeda.videos.domain

class EmptyVideoFileException : RuntimeException("el archivo de video está vacío")

class InvalidVideoTransitionException(val videoId: VideoId, val from: VideoStatus, val attempted: String) :
    RuntimeException("no se puede $attempted un video en estado $from (${videoId.value})")
