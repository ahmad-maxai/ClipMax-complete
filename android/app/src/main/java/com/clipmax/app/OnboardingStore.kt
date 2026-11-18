package com.clipmax.app

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import org.json.JSONObject

/**
 * Isolated Android persistence and real system IME verification for ClipMax onboarding.
 * Does not touch CapturePreferences, ClipStore, or any clipboard retention settings.
 */
internal object OnboardingStore {

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(
            OnboardingPolicy.PREF_FILE,
            Context.MODE_PRIVATE
        )

    fun resolveImeComponent(context: Context): ComponentName =
        ComponentName(context.applicationContext, ClipMaxImeService::class.java)

    fun isClipMaxImeEnabled(context: Context): Boolean {
        val component = resolveImeComponent(context)
        val pkg = component.packageName
        val cls = component.className

        val fromSecureSettings = runCatching {
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            )
            OnboardingPolicy.isComponentInColonList(pkg, cls, raw)
        }.getOrDefault(false)

        if (fromSecureSettings) return true

        return runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.any { info ->
                info.component == component ||
                    OnboardingPolicy.componentsMatch(pkg, cls, info.id)
            } ?: false
        }.getOrDefault(false)
    }

    fun isClipMaxImeSelected(context: Context): Boolean {
        val component = resolveImeComponent(context)
        val pkg = component.packageName
        val cls = component.className

        return runCatching {
            val defaultIme = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            if (defaultIme.isNullOrBlank()) return false
            val parsedComponent = ComponentName.unflattenFromString(defaultIme.trim())
            parsedComponent == component ||
                OnboardingPolicy.componentsMatch(pkg, cls, defaultIme)
        }.getOrDefault(false)
    }

    fun getReconciledState(context: Context): OnboardingPolicy.State {
        val p = prefs(context)
        val completed = p.getBoolean(OnboardingPolicy.KEY_IS_COMPLETED, false)
        val persistedStep = OnboardingPolicy.Step.fromWire(
            p.getString(OnboardingPolicy.KEY_STEP, OnboardingPolicy.Step.INTRODUCTION.wireName)
        )
        val attemptedEnable = p.getBoolean(OnboardingPolicy.KEY_ATTEMPTED_ENABLE, false)
        val attemptedSelect = p.getBoolean(OnboardingPolicy.KEY_ATTEMPTED_SELECT, false)

        val enabled = isClipMaxImeEnabled(context)
        val selected = isClipMaxImeSelected(context)

        val reconciled = OnboardingPolicy.reconcileState(
            completed = completed,
            persistedStep = persistedStep,
            imeEnabled = enabled,
            imeSelected = selected,
            attemptedEnable = attemptedEnable,
            attemptedSelect = attemptedSelect
        )

        if (!reconciled.completed && reconciled.step != persistedStep) {
            p.edit()
                .putString(OnboardingPolicy.KEY_STEP, reconciled.step.wireName)
                .putBoolean(OnboardingPolicy.KEY_ATTEMPTED_ENABLE, reconciled.attemptedEnable)
                .putBoolean(OnboardingPolicy.KEY_ATTEMPTED_SELECT, reconciled.attemptedSelect)
                .apply()
        }

        return reconciled
    }

    fun advanceFromIntroduction(context: Context): OnboardingPolicy.State {
        val enabled = isClipMaxImeEnabled(context)
        val selected = isClipMaxImeSelected(context)
        val next = OnboardingPolicy.advanceFromIntroduction(enabled, selected)
        prefs(context).edit()
            .putBoolean(OnboardingPolicy.KEY_IS_COMPLETED, false)
            .putString(OnboardingPolicy.KEY_STEP, next.step.wireName)
            .putBoolean(OnboardingPolicy.KEY_ATTEMPTED_ENABLE, false)
            .putBoolean(OnboardingPolicy.KEY_ATTEMPTED_SELECT, false)
            .apply()
        return next
    }

    fun returnToIntroduction(context: Context): OnboardingPolicy.State {
        val enabled = isClipMaxImeEnabled(context)
        val selected = isClipMaxImeSelected(context)
        prefs(context).edit()
            .putBoolean(OnboardingPolicy.KEY_IS_COMPLETED, false)
            .putString(OnboardingPolicy.KEY_STEP, OnboardingPolicy.Step.INTRODUCTION.wireName)
            .apply()
        return OnboardingPolicy.State(
            completed = false,
            step = OnboardingPolicy.Step.INTRODUCTION,
            imeEnabled = enabled,
            imeSelected = selected,
            attemptedEnable = false,
            attemptedSelect = false
        )
    }

    fun markAttemptedEnable(context: Context) {
        prefs(context).edit()
            .putBoolean(OnboardingPolicy.KEY_ATTEMPTED_ENABLE, true)
            .apply()
    }

    fun markAttemptedSelect(context: Context) {
        prefs(context).edit()
            .putBoolean(OnboardingPolicy.KEY_ATTEMPTED_SELECT, true)
            .apply()
    }

    fun markCompleted(context: Context): OnboardingPolicy.State {
        val enabled = isClipMaxImeEnabled(context)
        val selected = isClipMaxImeSelected(context)
        prefs(context).edit()
            .putBoolean(OnboardingPolicy.KEY_IS_COMPLETED, true)
            .putString(OnboardingPolicy.KEY_STEP, OnboardingPolicy.Step.COMPLETION.wireName)
            .putBoolean(OnboardingPolicy.KEY_ATTEMPTED_ENABLE, false)
            .putBoolean(OnboardingPolicy.KEY_ATTEMPTED_SELECT, false)
            .apply()
        return OnboardingPolicy.State(
            completed = true,
            step = OnboardingPolicy.Step.COMPLETION,
            imeEnabled = enabled,
            imeSelected = selected,
            attemptedEnable = false,
            attemptedSelect = false
        )
    }

    fun toJson(state: OnboardingPolicy.State): String {
        return JSONObject().apply {
            put("completed", state.completed)
            put("step", state.step.wireName)
            put("imeEnabled", state.imeEnabled)
            put("imeSelected", state.imeSelected)
            put("attemptedEnable", state.attemptedEnable)
            put("attemptedSelect", state.attemptedSelect)
        }.toString()
    }
}
