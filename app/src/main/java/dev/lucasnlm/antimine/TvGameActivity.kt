package dev.lucasnlm.antimine

import android.content.DialogInterface
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.doOnLayout
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Transformations
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import dev.lucasnlm.antimine.common.level.models.Event
import dev.lucasnlm.antimine.common.level.models.Status
import dev.lucasnlm.antimine.common.level.repository.ISavesRepository
import dev.lucasnlm.antimine.common.level.viewmodel.GameViewModel
import dev.lucasnlm.antimine.core.cloud.CloudSaveManager
import dev.lucasnlm.antimine.core.models.Analytics
import dev.lucasnlm.antimine.core.models.Difficulty
import dev.lucasnlm.antimine.core.models.Score
import dev.lucasnlm.antimine.gameover.GameOverDialogFragment
import dev.lucasnlm.antimine.gameover.WinGameDialogFragment
import dev.lucasnlm.antimine.gameover.model.GameResult
import dev.lucasnlm.antimine.level.view.LevelFragment
import dev.lucasnlm.antimine.preferences.IPreferencesRepository
import dev.lucasnlm.antimine.tutorial.view.TutorialCompleteDialogFragment
import dev.lucasnlm.antimine.tutorial.view.TutorialLevelFragment
import dev.lucasnlm.antimine.ui.ThematicActivity
import dev.lucasnlm.external.IAnalyticsManager
import dev.lucasnlm.external.IFeatureFlagManager
import dev.lucasnlm.external.IInstantAppManager
import dev.lucasnlm.external.ReviewWrapper
import kotlinx.android.synthetic.main.activity_game.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class TvGameActivity : ThematicActivity(R.layout.activity_game_tv), DialogInterface.OnDismissListener {
    private val preferencesRepository: IPreferencesRepository by inject()
    private val analyticsManager: IAnalyticsManager by inject()
    private val instantAppManager: IInstantAppManager by inject()
    private val savesRepository: ISavesRepository by inject()
    private val reviewWrapper: ReviewWrapper by inject()
    private val featureFlagManager: IFeatureFlagManager by inject()

    val gameViewModel by viewModel<GameViewModel>()

    private val cloudSaveManager by inject<CloudSaveManager>()

    private var status: Status = Status.PreGame
    private var totalMines: Int = 0
    private var totalArea: Int = 0
    private var rightMines: Int = 0
    private var currentTime: Long = 0
    private var currentSaveId: Long = 0

    private val areaSizeMultiplier by lazy { preferencesRepository.squareSizeMultiplier() }
    private val currentRadius by lazy { preferencesRepository.squareRadius() }
    private val useHelp by lazy { preferencesRepository.useHelp() }

    private var gameToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

        bindViewModel()

        findViewById<FrameLayout>(R.id.levelContainer).doOnLayout {
            if (!isFinishing) {
                if (!preferencesRepository.isTutorialCompleted()) {
                    loadGameTutorial()
                } else {
                    loadGameFragment()
                }
            }
        }

        onOpenAppActions()
    }

    private fun bindViewModel() = gameViewModel.apply {
        Transformations
            .distinctUntilChanged(eventObserver)
            .observe(
                this@TvGameActivity,
                ::onGameEvent,
            )

        retryObserver.observe(
            this@TvGameActivity,
            {
                lifecycleScope.launch {
                    gameViewModel.retryGame(currentSaveId.toInt())
                }
            }
        )

        continueObserver.observe(
            this@TvGameActivity,
            {
                lifecycleScope.launch {
                    gameViewModel.onContinueFromGameOver()
                    eventObserver.postValue(Event.ResumeGame)
                }
            }
        )

        elapsedTimeSeconds.observe(
            this@TvGameActivity,
            {
                timer.apply {
                    visibility = if (it == 0L) View.GONE else View.VISIBLE
                    text = DateUtils.formatElapsedTime(it)
                }
                currentTime = it
            }
        )

        mineCount.observe(
            this@TvGameActivity,
            {
                minesCount.apply {
                    visibility = View.VISIBLE
                    text = it.toString()
                }
            }
        )

        field.observe(
            this@TvGameActivity,
            { area ->
                val mines = area.filter { it.hasMine }
                totalArea = area.count()
                totalMines = mines.count()
                rightMines = mines.count { it.mark.isFlag() }
            }
        )

        saveId.observe(
            this@TvGameActivity,
            {
                currentSaveId = it
            }
        )

        showNewGame.observe(
            this@TvGameActivity,
            {
                waitAndShowEndGameAlert(
                    gameResult = GameResult.Completed,
                    await = false,
                    canContinue = false,
                )
            }
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.keyCode == 0) {
            event.toString()
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        val willReset = restartIfNeed()

        if (!willReset) {
            if (status == Status.Running) {
                gameViewModel.run {
                    refreshUserPreferences()
                    resumeGame()
                }

                analyticsManager.sentEvent(Analytics.Resume)
            }
        }
    }

    override fun onPause() {
        super.onPause()

        if (status == Status.Running) {
            gameViewModel.pauseGame()
        }

        if (isFinishing) {
            analyticsManager.sentEvent(Analytics.Quit)
        }
    }

    private fun onOpenAppActions() {
        if (instantAppManager.isEnabled(applicationContext)) {
            // Instant App does nothing.
            savesRepository.setLimit(1)
        } else {
            preferencesRepository.incrementUseCount()

            if (preferencesRepository.getUseCount() > featureFlagManager.minUsageToReview) {
                reviewWrapper.startInAppReview(this)
            }
        }
    }

    private fun loadGameFragment() {
        supportFragmentManager.apply {
            findFragmentByTag(TutorialLevelFragment.TAG)?.let { it ->
                beginTransaction().apply {
                    remove(it)
                    commitAllowingStateLoss()
                }
            }

            if (findFragmentByTag(LevelFragment.TAG) == null) {
                beginTransaction().apply {
                    replace(R.id.levelContainer, LevelFragment(), LevelFragment.TAG)
                    setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                    commitAllowingStateLoss()
                }
            }
        }
    }

    private fun loadGameTutorial() {
        supportFragmentManager.apply {
            findFragmentById(R.id.levelContainer)?.let { it ->
                beginTransaction().apply {
                    remove(it)
                    commitAllowingStateLoss()
                }
            }

            beginTransaction().apply {
                replace(R.id.levelContainer, TutorialLevelFragment(), TutorialLevelFragment.TAG)
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                commitAllowingStateLoss()
            }
        }
    }

    private fun showCompletedTutorialDialog() {
        TutorialCompleteDialogFragment().run {
            showAllowingStateLoss(supportFragmentManager, TutorialCompleteDialogFragment.TAG)
        }
    }

    private fun showEndGameDialog(gameResult: GameResult, canContinue: Boolean) {
        val currentGameStatus = status
        if (currentGameStatus is Status.Over && !isFinishing) {
            if (supportFragmentManager.findFragmentByTag(GameOverDialogFragment.TAG) == null &&
                supportFragmentManager.findFragmentByTag(WinGameDialogFragment.TAG) == null
            ) {
                val score = currentGameStatus.score

                if (gameResult == GameResult.Victory) {
                    WinGameDialogFragment.newInstance(
                        gameResult,
                        canContinue,
                        score?.rightMines ?: 0,
                        score?.totalMines ?: 0,
                        currentGameStatus.time,
                        2
                    ).apply {
                        showAllowingStateLoss(supportFragmentManager, WinGameDialogFragment.TAG)
                    }
                } else {
                    GameOverDialogFragment.newInstance(
                        gameResult,
                        canContinue,
                        score?.rightMines ?: 0,
                        score?.totalMines ?: 0,
                        currentGameStatus.time,
                        1
                    ).apply {
                        showAllowingStateLoss(supportFragmentManager, GameOverDialogFragment.TAG)
                    }
                }
            }
        }
    }

    private fun showEndGameToast(gameResult: GameResult) {
        gameToast?.cancel()

        val message = when (gameResult) {
            GameResult.GameOver -> R.string.you_lost
            GameResult.Victory -> R.string.you_won
            GameResult.Completed -> R.string.you_finished
        }

        gameToast = Toast.makeText(this, message, Toast.LENGTH_LONG).apply {
            setGravity(Gravity.CENTER, 0, 0)
            show()
        }
    }

    private fun showEndGameAlert(gameResult: GameResult, canContinue: Boolean) {
        val canShowWindow = preferencesRepository.showWindowsWhenFinishGame()
        if (!isFinishing) {
            if (canShowWindow) {
                showEndGameDialog(gameResult, gameResult == GameResult.GameOver && canContinue)
            } else {
                if (gameResult != GameResult.Victory) {
                    gameViewModel.viewModelScope.launch {
                        gameViewModel.revealMines()
                    }
                }

                showEndGameToast(gameResult)
            }
        }
    }

    private fun waitAndShowEndGameAlert(gameResult: GameResult, await: Boolean, canContinue: Boolean) {
        if (await && gameViewModel.explosionDelay() != 0L) {
            lifecycleScope.launch {
                delay((gameViewModel.explosionDelay() * 0.3).toLong())
                showEndGameAlert(gameResult, canContinue)
            }
        } else {
            showEndGameAlert(gameResult, canContinue)
        }
    }

    private fun onGameEvent(event: Event) {
        when (event) {
            Event.ResumeGame -> {
                status = Status.Running
            }
            Event.StartNewGame -> {
                gameToast?.cancel()
                loadGameFragment()
                status = Status.PreGame
            }
            Event.Resume, Event.Running -> {
                status = Status.Running
                gameViewModel.runClock()
            }
            Event.StartTutorial -> {
                status = Status.PreGame
                gameViewModel.stopClock()
                loadGameTutorial()
            }
            Event.FinishTutorial -> {
                gameViewModel.startNewGame(Difficulty.Beginner)
                loadGameFragment()
                status = Status.Over(0, Score(4, 4, 25))
                analyticsManager.sentEvent(Analytics.TutorialCompleted)
                preferencesRepository.setCompleteTutorial(true)
                showCompletedTutorialDialog()
                cloudSaveManager.uploadSave()
            }
            Event.Victory -> {
                val score = Score(
                    rightMines,
                    totalMines,
                    totalArea
                )
                status = Status.Over(currentTime, score)
                gameViewModel.stopClock()
                gameViewModel.showAllEmptyAreas()
                gameViewModel.victory()

                lifecycleScope.launch {
                    gameViewModel.saveGame()
                    gameViewModel.saveStats()
                }

                cloudSaveManager.uploadSave()

                gameViewModel.addNewTip()

                waitAndShowEndGameAlert(
                    gameResult = GameResult.Victory,
                    await = false,
                    canContinue = false,
                )
            }
            Event.GameOver -> {
                val isResuming = (status == Status.PreGame)
                val score = Score(
                    rightMines,
                    totalMines,
                    totalArea
                )
                status = Status.Over(currentTime, score)
                gameViewModel.stopClock()

                val isGameCompleted = gameViewModel.isCompletedWithMistakes()
                cloudSaveManager.uploadSave()
                lifecycleScope.launch {
                    gameViewModel.gameOver(isResuming, !isGameCompleted)
                    gameViewModel.saveGame()
                    waitAndShowEndGameAlert(
                        gameResult = if (isGameCompleted) GameResult.Completed else GameResult.GameOver,
                        await = true,
                        canContinue = gameViewModel.hasUnknownMines(),
                    )
                }
            }
            else -> {
            }
        }
    }

    /**
     * If user change any accessibility preference, the game will restart the activity to
     * apply these changes.
     */
    private fun restartIfNeed(): Boolean {
        return (
            areaSizeMultiplier != preferencesRepository.squareSizeMultiplier() ||
                currentRadius != preferencesRepository.squareRadius() ||
                useHelp != preferencesRepository.useHelp()
            ).also {
            if (it) {
                finish()
                startActivity(intent)
                overridePendingTransition(0, 0)
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface?) {
        gameViewModel.run {
            refreshUserPreferences()
            resumeGame()
        }
    }

    companion object {
        val TAG = TvGameActivity::class.simpleName
    }
}
