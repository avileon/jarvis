package il.co.vibit.jarvis.core.skills

import il.co.vibit.jarvis.core.dialog.Reply
import il.co.vibit.jarvis.core.nlu.Command

/**
 * Extension point for future capabilities (Waze, music, Home Assistant, webhooks, a ChatGPT fallback…).
 * Skills are consulted in order for utterances the built-in offline commands did not understand.
 * Return null to pass. Skills that need the network must be fast-failing so offline use stays responsive;
 * long-running/cloud skills belong in the app layer (see AssistantEngine.fallback).
 */
interface Skill {
    val id: String
    fun handle(text: String, command: Command): Reply?
}
