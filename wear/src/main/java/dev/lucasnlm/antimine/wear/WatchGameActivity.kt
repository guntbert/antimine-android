package dev.lucasnlm.antimine.wear

import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientModeSupport
import androidx.wear.ambient.AmbientModeSupport.AmbientCallback
import androidx.wear.ambient.AmbientModeSupport.EXTRA_LOWBIT_AMBIENT
import dev.lucasnlm.antimine.R
import dev.lucasnlm.antimine.common.level.models.AmbientSettings
import dev.lucasnlm.antimine.common.level.models.Event
import dev.lucasnlm.antimine.common.level.models.Status
import dev.lucasnlm.antimine.common.level.utils.Clock
import dev.lucasnlm.antimine.common.level.viewmodel.GameViewModel
import kotlinx.android.synthetic.main.activity_level.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class WatchGameActivity : AppCompatActivity(R.layout.activity_level), AmbientModeSupport.AmbientCallbackProvider {
    private val gameViewModel by viewModel<GameViewModel>()

    private lateinit var currentLevelFragment: WatchLevelFragment

    private val clock = Clock()
    private var lastShownTime: String = ""
    private var status: Status = Status.PreGame

    private val ambientMode: AmbientCallback = object : AmbientCallback() {
        override fun onExitAmbient() {
            super.onExitAmbient()
            currentLevelFragment.setAmbientMode(
                AmbientSettings(
                    isAmbientMode = false,
                    isLowBitAmbient = false
                )
            )
        }

        override fun onEnterAmbient(ambientDetails: Bundle?) {
            super.onEnterAmbient(ambientDetails)
            val lowBit = ambientDetails?.getBoolean(EXTRA_LOWBIT_AMBIENT) ?: true
            currentLevelFragment.setAmbientMode(
                AmbientSettings(true, lowBit)
            )
            updateClockText(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AmbientModeSupport.attach(this)

        bindViewModel()
        loadGameFragment()
    }

    override fun onResume() {
        super.onResume()
        clock.start {
            lifecycleScope.launch {
                updateClockText()
            }
        }
    }

    private fun updateClockText(force: Boolean = false) {
        val dateFormat = DateFormat.getTimeFormat(applicationContext)
        val current = dateFormat.format(System.currentTimeMillis())
        if (force || lastShownTime != current) {
            messageText.text = current
            lastShownTime = current
        }
    }

    override fun onPause() {
        super.onPause()
        clock.stop()
    }

    private fun loadGameFragment() {
        supportFragmentManager.apply {
            popBackStack()

            findFragmentById(R.id.levelContainer)?.let { it ->
                beginTransaction().apply {
                    remove(it)
                    commitAllowingStateLoss()
                }
            }

            currentLevelFragment = WatchLevelFragment()

            beginTransaction().apply {
                replace(R.id.levelContainer, currentLevelFragment)
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                commitAllowingStateLoss()
            }
        }
    }

    private fun bindViewModel() = gameViewModel.apply {
        eventObserver.observe(
            this@WatchGameActivity,
            Observer {
                onGameEvent(it)
            }
        )

        levelSetup.observe(
            this@WatchGameActivity,
            Observer {
                if (status != Status.PreGame) {
                    loadGameFragment()
                }
            }
        )

        mineCount.observe(
            this@WatchGameActivity,
            Observer {
                if (it > 0) {
                    messageText.text = applicationContext.getString(R.string.mines_remaining, it)
                }
            }
        )
    }

    private fun onGameEvent(event: Event) {
        when (event) {
            Event.StartNewGame -> {
                status = Status.PreGame
                newGame.visibility = View.GONE
            }
            Event.Resume, Event.Running -> {
                status = Status.Running
                newGame.visibility = View.GONE
            }
            Event.Victory -> {
                status = Status.Over()

                messageText.text = getString(R.string.victory)
                waitAndShowNewGameButton()
            }
            Event.GameOver -> {
                status = Status.Over()
                gameViewModel.stopClock()

                GlobalScope.launch(context = Dispatchers.Main) {
                    messageText.text = getString(R.string.game_over)
                    waitAndShowNewGameButton()
                }
            }
            else -> { }
        }
    }

    private fun waitAndShowNewGameButton(wait: Long = DateUtils.SECOND_IN_MILLIS) {
        lifecycleScope.launch {
            delay(wait).run {
                if (status is Status.Over && !isFinishing) {
                    newGame.visibility = View.VISIBLE
                    newGame.setOnClickListener {
                        it.visibility = View.GONE
                        GlobalScope.launch {
                            gameViewModel.startNewGame()
                        }
                    }
                }
            }
        }
    }

    override fun getAmbientCallback(): AmbientCallback = ambientMode
}
