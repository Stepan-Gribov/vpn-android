package com.v2ray.ang.simple

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Repeated identical actions join; a different action waits instead of losing activation. */
internal class PanelOperationQueue(private val scope: CoroutineScope) {
    private var flight: Deferred<Boolean>?=null
    private var key=""
    @Synchronized fun submit(requestKey: String,action: suspend ()->Boolean): Deferred<Boolean> {
        val prior=flight?.takeIf { it.isActive }
        if(prior!=null && key==requestKey) return prior
        val next=scope.async(start=CoroutineStart.LAZY) { prior?.await(); action() }
        flight=next; key=requestKey; next.start(); return next
    }
}
