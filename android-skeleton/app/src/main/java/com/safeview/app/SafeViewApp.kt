package com.safeview.app

import android.app.Application

class SafeViewApp : Application() {
    lateinit var classifier: NsfwClassifier
        private set

    /**
     * True once the JS bridge can route page images into [NsfwClassifier.classify].
     * The model may still be missing; Settings enables AI only when both this flag
     * and [NsfwClassifier.isReady] are true.
     */
    val aiPipelineAvailable: Boolean = true

    override fun onCreate() {
        super.onCreate()
        classifier = NsfwClassifier(this)
        Thread {
            classifier.load()
        }.start()
    }
}
