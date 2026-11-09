package com.fitdeck.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private const val PREFS_NAME = "fitdeck"
private const val DEFAULT_WEIGHT_KG = 70f
private const val DEFAULT_DAILY_CARD_GOAL = 8
private const val DEFAULT_DAILY_CALORIE_GOAL = 150
private const val DEFAULT_REST_SECONDS = 45

private val EXERCISES = listOf("Push-ups", "Squats", "Sit-ups", "Burpees")

/** A card's rank determines repetitions; its suit determines the exercise. */
data class WorkoutCard(val rank: String, val suit: String) {
    val reps: Int get() = when (rank) {
        "A" -> 1
        "J" -> 11
        "Q" -> 12
        "K" -> 13
        else -> rank.toInt()
    }
    val exercise: String get() = when (suit) {
        "♠" -> "Push-ups"
        "♥" -> "Squats"
        "♦" -> "Sit-ups"
        else -> "Burpees"
    }
    val red: Boolean get() = suit == "♥" || suit == "♦"
    fun json() = JSONObject().put("rank", rank).put("suit", suit)
    companion object {
        fun from(o: JSONObject) = WorkoutCard(o.getString("rank"), o.getString("suit"))
    }
}

private data class WorkoutSession(
    val startedAt: Long,
    val completedAt: Long,
    val cards: Int,
    val reps: Map<String, Int>,
    val activeMs: Long,
    val calories: Double
) {
    fun json(): JSONObject {
        val repObject = JSONObject()
        reps.forEach { (name, count) -> repObject.put(name, count) }
        return JSONObject()
            .put("startedAt", startedAt)
            .put("completedAt", completedAt)
            .put("cards", cards)
            .put("reps", repObject)
            .put("activeMs", activeMs)
            .put("calories", calories)
    }

    companion object {
        fun from(o: JSONObject): WorkoutSession {
            val repObject = o.optJSONObject("reps") ?: JSONObject()
            val reps = EXERCISES.associateWith { repObject.optInt(it, 0) }
            return WorkoutSession(
                o.optLong("startedAt"), o.optLong("completedAt"), o.optInt("cards"),
                reps, o.optLong("activeMs"), o.optDouble("calories")
            )
        }
    }
}

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val deck = mutableListOf<WorkoutCard>()
    private val history = mutableListOf<WorkoutCard>()
    private val sessions = mutableListOf<WorkoutSession>()

    private var current: WorkoutCard? = null
    private var reshuffles = 0
    private var workoutSaved = false

    // The workout stopwatch counts active time only. Explicit rest periods are excluded.
    private var sessionStartedAt = 0L
    private var accumulatedActiveMs = 0L
    private var activeSegmentStartedAt = 0L
    private var workoutPaused = false
    private var isResting = false
    private var restEndsAt = 0L

    private var weightKg = DEFAULT_WEIGHT_KG
    private var dailyCardGoal = DEFAULT_DAILY_CARD_GOAL
    private var dailyCalorieGoal = DEFAULT_DAILY_CALORIE_GOAL
    private var restSeconds = DEFAULT_REST_SECONDS

    private lateinit var cardFace: TextView
    private lateinit var exerciseText: TextView
    private lateinit var progressText: TextView
    private lateinit var remainingText: TextView
    private lateinit var summaryText: TextView
    private lateinit var historyText: TextView
    private lateinit var timerText: TextView
    private lateinit var restText: TextView
    private lateinit var caloriesText: TextView
    private lateinit var dailyProgressText: TextView
    private lateinit var streakText: TextView
    private lateinit var weeklyChartText: TextView
    private lateinit var monthlyChartText: TextView
    private lateinit var sessionsText: TextView
    private lateinit var drawButton: Button
    private lateinit var pauseButton: Button
    private lateinit var restButton: Button
    private lateinit var finishButton: Button
    private lateinit var weightGoalText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            advanceRestIfFinished(System.currentTimeMillis())
            updateTimerUi()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadState()
        buildUi()
        render()
        handler.post(timerTick)
    }

    override fun onResume() {
        super.onResume()
        advanceRestIfFinished(System.currentTimeMillis())
        updateTimerUi()
        handler.removeCallbacks(timerTick)
        handler.post(timerTick)
    }

    override fun onPause() {
        saveState()
        handler.removeCallbacks(timerTick)
        super.onPause()
    }

    private fun makeDeck(): MutableList<WorkoutCard> {
        val ranks = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
        val suits = listOf("♠", "♥", "♦", "♣")
        return suits.flatMap { suit -> ranks.map { rank -> WorkoutCard(rank, suit) } }.toMutableList()
    }

    private fun shuffleRemaining() {
        deck.shuffle()
        reshuffles++
    }

    private fun newWorkout() {
        archiveCurrentWorkout()
        deck.clear()
        deck.addAll(makeDeck())
        deck.shuffle()
        history.clear()
        current = null
        reshuffles = 0
        workoutSaved = false
        sessionStartedAt = 0L
        accumulatedActiveMs = 0L
        activeSegmentStartedAt = 0L
        workoutPaused = false
        isResting = false
        restEndsAt = 0L
        saveState()
        render()
    }

    private fun beginWorkoutTimerIfNeeded() {
        if (sessionStartedAt == 0L) {
            val now = System.currentTimeMillis()
            sessionStartedAt = now
            activeSegmentStartedAt = now
            accumulatedActiveMs = 0L
            workoutPaused = false
            isResting = false
        }
    }

    private fun drawCard() {
        if (deck.isEmpty() || workoutSaved || isResting || workoutPaused) return
        beginWorkoutTimerIfNeeded()
        current = deck.removeAt(0)
        history.add(current!!)
        // After every 8 draws, shuffle only the cards that have not been drawn.
        if (history.size % 8 == 0 && deck.isNotEmpty()) shuffleRemaining()
        if (deck.isEmpty()) {
            archiveCurrentWorkout()
        }
        saveState()
        render()
    }

    private fun finishWorkout() {
        archiveCurrentWorkout()
        saveState()
        render()
    }

    private fun archiveCurrentWorkout() {
        if (history.isEmpty() || workoutSaved) return
        val now = System.currentTimeMillis()
        val activeMs = getActiveElapsedMs(now)
        val reps = exerciseTotals()
        val calories = estimateCalories(activeMs, reps)
        // Freeze the final active duration so the completed-session summary stays intact.
        accumulatedActiveMs = activeMs
        sessions.add(
            WorkoutSession(
                startedAt = if (sessionStartedAt == 0L) now else sessionStartedAt,
                completedAt = now,
                cards = history.size,
                reps = reps,
                activeMs = activeMs,
                calories = calories
            )
        )
        // Keep a practical local history size while preserving recent sessions.
        while (sessions.size > 500) sessions.removeAt(0)
        workoutSaved = true
        activeSegmentStartedAt = 0L
        workoutPaused = false
        isResting = false
        restEndsAt = 0L
    }

    private fun startRest() {
        if (sessionStartedAt == 0L || workoutSaved || history.isEmpty() || isResting || workoutPaused) return
        val now = System.currentTimeMillis()
        accumulatedActiveMs = getActiveElapsedMs(now)
        activeSegmentStartedAt = 0L
        isResting = true
        restEndsAt = now + restSeconds * 1000L
        saveState()
        updateTimerUi()
        renderButtonsOnly()
    }

    private fun skipRest() {
        if (!isResting) return
        val now = System.currentTimeMillis()
        isResting = false
        restEndsAt = 0L
        if (!workoutPaused && !workoutSaved) activeSegmentStartedAt = now
        saveState()
        updateTimerUi()
        renderButtonsOnly()
    }

    private fun togglePause() {
        if (sessionStartedAt == 0L || workoutSaved || isResting) return
        val now = System.currentTimeMillis()
        if (workoutPaused) {
            workoutPaused = false
            activeSegmentStartedAt = now
        } else {
            accumulatedActiveMs = getActiveElapsedMs(now)
            activeSegmentStartedAt = 0L
            workoutPaused = true
        }
        saveState()
        updateTimerUi()
        renderButtonsOnly()
    }

    private fun advanceRestIfFinished(now: Long) {
        if (isResting && restEndsAt > 0L && now >= restEndsAt) {
            val restEnd = restEndsAt
            isResting = false
            restEndsAt = 0L
            if (!workoutPaused && !workoutSaved) activeSegmentStartedAt = restEnd
            saveState()
        }
    }

    private fun getActiveElapsedMs(now: Long = System.currentTimeMillis()): Long {
        advanceRestIfFinished(now)
        return accumulatedActiveMs + if (activeSegmentStartedAt > 0L && !workoutPaused && !isResting && !workoutSaved) {
            max(0L, now - activeSegmentStartedAt)
        } else 0L
    }

    private fun estimateCalories(activeMs: Long, reps: Map<String, Int>): Double {
        if (activeMs <= 0L || reps.values.sum() <= 0) return 0.0
        // Approximate MET values for moderate-to-vigorous bodyweight exercise.
        // This is an estimate, not a measurement from a heart-rate sensor.
        val met = mapOf("Push-ups" to 8.0, "Squats" to 5.0, "Sit-ups" to 3.8, "Burpees" to 8.0)
        val totalReps = reps.values.sum().toDouble()
        val averageMet = reps.entries.sumOf { (exercise, count) -> (met[exercise] ?: 5.0) * count } / totalReps
        val minutes = activeMs / 60000.0
        return averageMet * 3.5 * weightKg / 200.0 * minutes
    }

    private fun exerciseTotals(): Map<String, Int> = EXERCISES.associateWith { name ->
        history.filter { it.exercise == name }.sumOf { it.reps }
    }

    private fun saveState() {
        val d = JSONArray()
        deck.forEach { d.put(it.json()) }
        val h = JSONArray()
        history.forEach { h.put(it.json()) }
        val s = JSONArray()
        sessions.forEach { s.put(it.json()) }
        prefs.edit()
            .putString("deck", d.toString())
            .putString("history", h.toString())
            .putString("sessions", s.toString())
            .putInt("reshuffles", reshuffles)
            .putBoolean("workoutSaved", workoutSaved)
            .putLong("sessionStartedAt", sessionStartedAt)
            .putLong("accumulatedActiveMs", accumulatedActiveMs)
            .putLong("activeSegmentStartedAt", activeSegmentStartedAt)
            .putBoolean("workoutPaused", workoutPaused)
            .putBoolean("isResting", isResting)
            .putLong("restEndsAt", restEndsAt)
            .putFloat("weightKg", weightKg)
            .putInt("dailyCardGoal", dailyCardGoal)
            .putInt("dailyCalorieGoal", dailyCalorieGoal)
            .putInt("restSeconds", restSeconds)
            .apply()
    }

    private fun loadState() {
        weightKg = prefs.getFloat("weightKg", DEFAULT_WEIGHT_KG)
        dailyCardGoal = prefs.getInt("dailyCardGoal", DEFAULT_DAILY_CARD_GOAL)
        dailyCalorieGoal = prefs.getInt("dailyCalorieGoal", DEFAULT_DAILY_CALORIE_GOAL)
        restSeconds = prefs.getInt("restSeconds", DEFAULT_REST_SECONDS)

        try {
            val deckString = prefs.getString("deck", null)
            val historyString = prefs.getString("history", null)
            if (deckString == null || historyString == null) {
                deck.addAll(makeDeck())
                deck.shuffle()
            } else {
                val d = JSONArray(deckString)
                for (i in 0 until d.length()) deck.add(WorkoutCard.from(d.getJSONObject(i)))
                val h = JSONArray(historyString)
                for (i in 0 until h.length()) history.add(WorkoutCard.from(h.getJSONObject(i)))
                if (deck.size + history.size != 52) {
                    deck.clear(); history.clear(); deck.addAll(makeDeck()); deck.shuffle()
                }
            }

            val sessionJson = JSONArray(prefs.getString("sessions", "[]") ?: "[]")
            for (i in 0 until sessionJson.length()) sessions.add(WorkoutSession.from(sessionJson.getJSONObject(i)))
            reshuffles = prefs.getInt("reshuffles", 0)
            workoutSaved = prefs.getBoolean("workoutSaved", false)
            sessionStartedAt = prefs.getLong("sessionStartedAt", 0L)
            accumulatedActiveMs = prefs.getLong("accumulatedActiveMs", 0L)
            activeSegmentStartedAt = prefs.getLong("activeSegmentStartedAt", 0L)
            workoutPaused = prefs.getBoolean("workoutPaused", false)
            isResting = prefs.getBoolean("isResting", false)
            restEndsAt = prefs.getLong("restEndsAt", 0L)
            current = history.lastOrNull()
            advanceRestIfFinished(System.currentTimeMillis())
        } catch (_: Exception) {
            deck.clear(); history.clear()
            deck.addAll(makeDeck()); deck.shuffle()
            current = null
            workoutSaved = false
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(30))
            setBackgroundColor(Color.rgb(14, 22, 36))
        }
        scroll.addView(page)
        setContentView(scroll)

        page.addView(label("FIT DECK", 31, Color.WHITE, true, Gravity.CENTER))
        page.addView(label("YOUR 52-CARD BODYWEIGHT CHALLENGE", 11, 0xFF9CA3AF.toInt(), true, Gravity.CENTER).apply {
            setPadding(0, dp(4), 0, dp(18))
        })

        val settings = button("BODY WEIGHT & DAILY GOALS", 0xFF334155.toInt())
        settings.setOnClickListener { showSettingsDialog() }
        page.addView(settings, matchWrap())
        weightGoalText = label("", 12, 0xFFCBD5E1.toInt(), false, Gravity.CENTER).apply {
            setPadding(0, dp(7), 0, dp(16))
        }
        page.addView(weightGoalText)

        val legend = panel()
        listOf("♠  PUSH-UPS", "♥  SQUATS", "♦  SIT-UPS", "♣  BURPEES").forEach { item ->
            legend.addView(label(item, 14, if (item.startsWith("♥") || item.startsWith("♦")) 0xFFF87171.toInt() else Color.WHITE, true, Gravity.CENTER).apply {
                setPadding(0, dp(2), 0, dp(2))
            })
        }
        page.addView(legend, matchWrap())

        cardFace = label("🂠", 78, 0xFF111827.toInt(), true, Gravity.CENTER).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(27), dp(10), dp(25))
        }
        page.addView(cardFace, matchWrap().apply { topMargin = dp(20) })

        exerciseText = label("Tap DRAW CARD to begin", 21, Color.WHITE, true, Gravity.CENTER).apply {
            setPadding(0, dp(14), 0, dp(14))
        }
        page.addView(exerciseText)
        progressText = label("", 14, 0xFFE2E8F0.toInt(), true, Gravity.CENTER)
        page.addView(progressText)
        remainingText = label("", 12, 0xFF9CA3AF.toInt(), false, Gravity.CENTER).apply {
            setPadding(0, dp(4), 0, dp(14))
        }
        page.addView(remainingText)

        drawButton = button("DRAW NEXT CARD", 0xFF16A34A.toInt())
        drawButton.setOnClickListener { drawCard() }
        page.addView(drawButton, matchWrap())
        restButton = button("START ${restSeconds}s REST", 0xFF2563EB.toInt())
        restButton.setOnClickListener { if (isResting) skipRest() else startRest() }
        page.addView(restButton, matchWrap().apply { topMargin = dp(9) })
        pauseButton = button("PAUSE WORKOUT TIMER", 0xFF475569.toInt())
        pauseButton.setOnClickListener { togglePause() }
        page.addView(pauseButton, matchWrap().apply { topMargin = dp(9) })
        finishButton = button("FINISH & SAVE WORKOUT", 0xFF7C3AED.toInt())
        finishButton.setOnClickListener { finishWorkout() }
        page.addView(finishButton, matchWrap().apply { topMargin = dp(9) })
        val restart = button("SHUFFLE / NEW WORKOUT", 0xFF334155.toInt())
        restart.setOnClickListener { newWorkout() }
        page.addView(restart, matchWrap().apply { topMargin = dp(9) })

        page.addView(sectionTitle("WORKOUT TIMER"))
        val timerPanel = panel()
        timerText = label("00:00", 32, Color.WHITE, true, Gravity.CENTER)
        restText = label("Active workout time. Rest periods are excluded.", 12, 0xFFCBD5E1.toInt(), false, Gravity.CENTER)
        timerPanel.addView(timerText)
        timerPanel.addView(restText)
        page.addView(timerPanel, matchWrap())

        page.addView(sectionTitle("SESSION SUMMARY"))
        val statsPanel = panel()
        caloriesText = label("", 17, 0xFF86EFAC.toInt(), true, Gravity.CENTER)
        summaryText = label("", 14, 0xFFE5E7EB.toInt(), false, Gravity.CENTER).apply {
            setPadding(0, dp(8), 0, 0)
        }
        statsPanel.addView(caloriesText)
        statsPanel.addView(summaryText)
        page.addView(statsPanel, matchWrap())

        page.addView(sectionTitle("DAILY GOALS & STREAK"))
        val dailyPanel = panel()
        dailyProgressText = label("", 14, Color.WHITE, false, Gravity.CENTER)
        streakText = label("", 14, 0xFFFDE68A.toInt(), true, Gravity.CENTER).apply {
            setPadding(0, dp(8), 0, 0)
        }
        dailyPanel.addView(dailyProgressText)
        dailyPanel.addView(streakText)
        page.addView(dailyPanel, matchWrap())

        page.addView(sectionTitle("WEEKLY CALORIES"))
        weeklyChartText = label("", 12, 0xFFE2E8F0.toInt(), false, Gravity.LEFT)
        weeklyChartText.setBackgroundColor(0xFF202C40.toInt())
        weeklyChartText.setPadding(dp(12), dp(12), dp(12), dp(12))
        page.addView(weeklyChartText, matchWrap())

        page.addView(sectionTitle("MONTHLY CALORIES"))
        monthlyChartText = label("", 12, 0xFFE2E8F0.toInt(), false, Gravity.LEFT)
        monthlyChartText.setBackgroundColor(0xFF202C40.toInt())
        monthlyChartText.setPadding(dp(12), dp(12), dp(12), dp(12))
        page.addView(monthlyChartText, matchWrap())

        page.addView(sectionTitle("WORKOUT HISTORY"))
        sessionsText = label("No completed workouts yet.", 13, 0xFFD1D5DB.toInt(), false, Gravity.LEFT)
        sessionsText.setBackgroundColor(0xFF202C40.toInt())
        sessionsText.setPadding(dp(12), dp(12), dp(12), dp(12))
        page.addView(sessionsText, matchWrap())

        page.addView(sectionTitle("CURRENT DRAW HISTORY"))
        historyText = label("No cards drawn yet.", 13, 0xFFD1D5DB.toInt(), false, Gravity.CENTER)
        page.addView(historyText, matchWrap())

        page.addView(sectionTitle("EXERCISE QUICK GUIDE"))
        val guide = panel()
        listOf(
            "♠ Push-ups — Keep your body in a straight line; lower with control and press the floor away.",
            "♥ Squats — Keep your feet planted; sit hips back and lower only as far as you can maintain control.",
            "♦ Sit-ups — Move smoothly without pulling your neck; use a controlled return.",
            "♣ Burpees — Squat, place hands down, step or jump to plank, return to squat and stand."
        ).forEach { guide.addView(label(it, 12, 0xFFE2E8F0.toInt(), false, Gravity.LEFT).apply { setPadding(0, dp(4), 0, dp(4)) }) }
        page.addView(guide, matchWrap())

        page.addView(label("Offline by design • Your data stays on this device • Calories are estimates", 10, 0xFF9CA3AF.toInt(), false, Gravity.CENTER).apply {
            setPadding(0, dp(22), 0, 0)
        })
    }

    private fun render() {
        val done = history.size
        val card = current
        if (card == null) {
            cardFace.text = "🂠"
            cardFace.setTextColor(0xFF111827.toInt())
            exerciseText.text = if (done == 0) "Ready when you are" else "Workout in progress"
        } else {
            cardFace.text = "${card.rank}${card.suit}"
            cardFace.setTextColor(if (card.red) 0xFFDC2626.toInt() else 0xFF111827.toInt())
            exerciseText.text = "${card.reps} ${card.exercise.uppercase(Locale.getDefault())}"
        }
        if (deck.isEmpty() && done > 0) exerciseText.text = "DECK COMPLETE! GREAT WORK."
        progressText.text = "CARDS COMPLETED   $done / 52"
        remainingText.text = "${deck.size} cards remaining  •  ${reshuffles} remaining-deck shuffles"

        val totals = exerciseTotals()
        val repsTotal = totals.values.sum()
        val activeMs = getActiveElapsedMs()
        val calories = estimateCalories(activeMs, totals)
        summaryText.text = EXERCISES.joinToString("\n") { "$it: ${totals[it] ?: 0} reps" } +
            "\n\nTOTAL REPETITIONS: $repsTotal"
        caloriesText.text = "ESTIMATED CALORIES: ${calories.roundToInt()} kcal"
        historyText.text = if (history.isEmpty()) "No cards drawn yet." else
            history.asReversed().take(28).joinToString("     ") { "${it.rank}${it.suit}" } +
                if (history.size > 28) "\n… and ${history.size - 28} earlier cards" else ""

        weightGoalText.text = "Body weight: ${formatWeight(weightKg)} kg   •   Daily goal: $dailyCardGoal cards / $dailyCalorieGoal kcal"
        renderDailyProgress(calories)
        renderCharts()
        renderSessions()
        renderButtonsOnly()
        updateTimerUi()
    }

    private fun renderButtonsOnly() {
        val canDraw = deck.isNotEmpty() && !workoutSaved && !isResting && !workoutPaused
        drawButton.isEnabled = canDraw
        drawButton.alpha = if (canDraw) 1f else 0.45f
        restButton.isEnabled = sessionStartedAt > 0L && history.isNotEmpty() && !workoutSaved && !workoutPaused
        restButton.text = if (isResting) "SKIP REST" else "START ${restSeconds}s REST"
        pauseButton.isEnabled = sessionStartedAt > 0L && !workoutSaved && !isResting
        pauseButton.text = if (workoutPaused) "RESUME WORKOUT TIMER" else "PAUSE WORKOUT TIMER"
        finishButton.isEnabled = history.isNotEmpty() && !workoutSaved
        finishButton.alpha = if (finishButton.isEnabled) 1f else 0.45f
    }

    private fun updateTimerUi() {
        if (!::timerText.isInitialized) return
        val elapsed = getActiveElapsedMs()
        timerText.text = formatDuration(elapsed)
        restText.text = when {
            isResting -> "REST TIME: ${max(0L, (restEndsAt - System.currentTimeMillis() + 999L) / 1000L)} seconds remaining"
            workoutPaused -> "Workout timer paused"
            sessionStartedAt == 0L -> "Timer starts automatically with your first card"
            workoutSaved -> "Workout saved"
            else -> "Active workout time • Rest periods are excluded"
        }
    }

    private fun renderDailyProgress(currentCalories: Double) {
        val today = dateKey(System.currentTimeMillis())
        val todaySessions = sessions.filter { dateKey(it.completedAt) == today }
        val activeUnfinished = history.isNotEmpty() && !workoutSaved && sessionStartedAt > 0L && dateKey(sessionStartedAt) == today
        val cardsToday = todaySessions.sumOf { it.cards } + if (activeUnfinished) history.size else 0
        val caloriesToday = todaySessions.sumOf { it.calories } + if (activeUnfinished) currentCalories else 0.0
        val cardPct = (cardsToday * 100 / max(1, dailyCardGoal)).coerceAtMost(100)
        val caloriePct = (caloriesToday * 100.0 / max(1, dailyCalorieGoal)).roundToInt().coerceAtMost(100)
        dailyProgressText.text = "CARDS: $cardsToday / $dailyCardGoal  ($cardPct%)\n" +
            "CALORIES: ${caloriesToday.roundToInt()} / $dailyCalorieGoal kcal  ($caloriePct%)"

        val activeDates = sessions.map { dateKey(it.completedAt) }.toMutableSet()
        if (activeUnfinished) activeDates.add(today)
        var cursor = Calendar.getInstance()
        if (!activeDates.contains(today)) cursor.add(Calendar.DAY_OF_YEAR, -1)
        var streak = 0
        while (activeDates.contains(dateKey(cursor.timeInMillis)) && streak < 366) {
            streak++
            cursor.add(Calendar.DAY_OF_YEAR, -1)
        }
        streakText.text = "CURRENT STREAK: $streak ${if (streak == 1) "DAY" else "DAYS"}"
    }

    private fun renderCharts() {
        val calendar = Calendar.getInstance()
        val weeklyRows = mutableListOf<String>()
        val dailyValues = (6 downTo 0).map { daysAgo ->
            val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
            val key = dateKey(c.timeInMillis)
            val value = sessions.filter { dateKey(it.completedAt) == key }.sumOf { it.calories }
            Pair(c, value)
        }
        val weekMax = max(1.0, dailyValues.maxOfOrNull { it.second } ?: 1.0)
        dailyValues.forEach { (c, value) ->
            val day = SimpleDateFormat("EEE", Locale.getDefault()).format(c.time)
            weeklyRows.add("${day.padEnd(4)} ${bar(value / weekMax, 12)}  ${value.roundToInt()} kcal")
        }
        weeklyChartText.text = weeklyRows.joinToString("\n")

        val monthly = mutableListOf<Pair<String, Double>>()
        for (monthsAgo in 5 downTo 0) {
            val c = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, -monthsAgo)
            }
            val year = c.get(Calendar.YEAR)
            val month = c.get(Calendar.MONTH)
            val value = sessions.filter {
                val sessionCal = Calendar.getInstance().apply { timeInMillis = it.completedAt }
                sessionCal.get(Calendar.YEAR) == year && sessionCal.get(Calendar.MONTH) == month
            }.sumOf { it.calories }
            monthly.add(Pair(SimpleDateFormat("MMM", Locale.getDefault()).format(c.time), value))
        }
        val monthMax = max(1.0, monthly.maxOfOrNull { it.second } ?: 1.0)
        monthlyChartText.text = monthly.joinToString("\n") { (month, value) ->
            "${month.padEnd(4)} ${bar(value / monthMax, 12)}  ${value.roundToInt()} kcal"
        }
    }

    private fun renderSessions() {
        if (sessions.isEmpty()) {
            sessionsText.text = "No completed workouts yet. Finish a workout to save it here."
            return
        }
        val fmt = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())
        sessionsText.text = sessions.asReversed().take(12).joinToString("\n\n") { session ->
            "${fmt.format(Date(session.completedAt))}\n" +
                "${session.cards} cards • ${session.reps.values.sum()} reps • ${formatDuration(session.activeMs)} active • ${session.calories.roundToInt()} kcal"
        }
    }

    private fun showSettingsDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        fun addField(caption: String, value: String, decimal: Boolean = false): EditText {
            box.addView(label(caption, 13, 0xFF111827.toInt(), true, Gravity.LEFT).apply {
                setPadding(0, dp(8), 0, dp(3))
            })
            return EditText(this).apply {
                setSingleLine(true)
                inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
                setText(value)
                box.addView(this, matchWrap())
            }
        }
        val weightField = addField("Body weight (kg)", formatWeight(weightKg), true)
        val cardsField = addField("Daily card goal", dailyCardGoal.toString())
        val caloriesField = addField("Daily calorie goal (kcal)", dailyCalorieGoal.toString())
        val restField = addField("Rest timer (seconds)", restSeconds.toString())
        AlertDialog.Builder(this)
            .setTitle("Body weight & daily goals")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val newWeight = weightField.text.toString().toFloatOrNull()
                val newCards = cardsField.text.toString().toIntOrNull()
                val newCalories = caloriesField.text.toString().toIntOrNull()
                val newRest = restField.text.toString().toIntOrNull()
                if (newWeight != null && newWeight in 25f..300f) weightKg = newWeight
                if (newCards != null && newCards in 1..52) dailyCardGoal = newCards
                if (newCalories != null && newCalories in 1..5000) dailyCalorieGoal = newCalories
                if (newRest != null && newRest in 10..600) restSeconds = newRest
                saveState()
                render()
            }
            .show()
    }

    private fun bar(fraction: Double, width: Int): String {
        val filled = (fraction.coerceIn(0.0, 1.0) * width).roundToInt()
        return "▰".repeat(filled) + "▱".repeat(width - filled)
    }

    private fun dateKey(time: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time))
    private fun formatWeight(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)
    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = max(0L, milliseconds / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun sectionTitle(text: String) = label(text, 16, Color.WHITE, true, Gravity.CENTER).apply {
        setPadding(0, dp(22), 0, dp(10))
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(13), dp(13), dp(13))
        setBackgroundColor(0xFF202C40.toInt())
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean, gravity: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            this.gravity = gravity
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(text: String, color: Int) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        textSize = 13f
        isAllCaps = false
        minHeight = dp(52)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
