# FIT DECK — Android source project

An offline Android workout-card app. All workout data is stored locally on the device using SharedPreferences. The app does not request network access.

## Card workout rules
- Standard 52-card deck: 13 ranks × 4 suits.
- ♠ Push-ups, ♥ Squats, ♦ Sit-ups, ♣ Burpees.
- Repetitions: A=1, 2–10=face value, J=11, Q=12, K=13.
- A drawn card is removed and cannot repeat in the same workout.
- After every 8 draws, only the remaining undrawn cards are shuffled.
- Starting a new workout creates and shuffles a fresh 52-card deck.

## Fitness features
- **Calorie estimation:** estimates energy expenditure from active workout time, body weight, and an exercise-mix MET estimate. Explicit rest periods are excluded from active time. This is an estimate, not a sensor measurement.
- **Workout history:** saves completed sessions, card count, repetitions, active duration, and estimated calories.
- **Body-weight settings:** stores body weight locally and uses it in calorie estimates.
- **Workout timer:** starts on the first card and can be paused/resumed.
- **Rest timer:** configurable countdown (default 45 seconds); starting a rest pauses active-time accumulation. Rest can be skipped.
- **Daily goals and streaks:** configurable daily card and calorie goals, plus consecutive-day workout streak.
- **Weekly/monthly charts:** local calorie totals for the last seven days and last six months.
- **Exercise quick guide:** concise form reminders for the four included movements.

## Calorie-estimation notes
The app uses approximate MET assumptions for the four exercise categories (push-ups 8.0, squats 5.0, sit-ups 3.8, burpees 8.0), weighted by the repetitions drawn in the session. The estimate uses:

`Calories = average MET × 3.5 × body weight (kg) ÷ 200 × active minutes`

Actual energy expenditure varies with pace, technique, fitness, range of motion, and individual physiology. The result should be treated as a rough estimate, not a medical or wearable-device reading.

## Build the APK
1. Install Android Studio with Android SDK Platform 35.
2. Open this project folder in Android Studio.
3. Allow Gradle sync to finish.
4. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

You can also push the project to GitHub and run the included GitHub Actions workflow (`.github/workflows/build-apk.yml`) to build a debug APK in the cloud.

This source package is not itself a compiled APK. A local Android SDK/Gradle build or the GitHub Actions workflow is required to produce the installable APK.
