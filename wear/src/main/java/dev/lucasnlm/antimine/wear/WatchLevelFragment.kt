package dev.lucasnlm.antimine.wear

import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import dev.lucasnlm.antimine.common.R
import dev.lucasnlm.antimine.common.level.models.AmbientSettings
import dev.lucasnlm.antimine.common.level.models.Event
import dev.lucasnlm.antimine.common.level.view.CommonLevelFragment
import dev.lucasnlm.antimine.common.level.view.SpaceItemDecoration
import kotlinx.coroutines.launch

class WatchLevelFragment : CommonLevelFragment(R.layout.fragment_level) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerGrid = view.findViewById(R.id.recyclerGrid)

        lifecycleScope.launchWhenCreated {
            val levelSetup = gameViewModel.loadLastGame()
            recyclerGrid.doOnLayout {
                lifecycleScope.launch {
                    recyclerGrid.apply {
                        val horizontalPadding = calcHorizontalPadding(levelSetup.width)
                        val verticalPadding = calcVerticalPadding(levelSetup.height)
                        setHasFixedSize(true)
                        addItemDecoration(SpaceItemDecoration(R.dimen.field_padding))
                        setPadding(horizontalPadding, verticalPadding, 0, 0)
                        layoutManager = makeNewLayoutManager(levelSetup.width)
                        adapter = areaAdapter
                        alpha = 0.0f

                        val dy = calcVerticalScrollToCenter(levelSetup.height)
                        val dx = calcHorizontalScrollToCenter(levelSetup.width)
                        smoothScrollBy(dx, dy, null, 0)

                        animate().apply {
                            alpha(1.0f)
                            duration = DateUtils.SECOND_IN_MILLIS
                        }.start()
                    }
                }
            }
        }

        gameViewModel.run {
            field.observe(
                viewLifecycleOwner,
                Observer {
                    areaAdapter.bindField(it)
                }
            )
            eventObserver.observe(
                viewLifecycleOwner,
                Observer {
                    if (it == Event.StartNewGame) {
                        recyclerGrid.scrollToPosition(areaAdapter.itemCount / 2)
                    }

                    when (it) {
                        Event.GameOver,
                        Event.Victory -> areaAdapter.setClickEnabled(false)
                        else -> areaAdapter.setClickEnabled(true)
                    }
                }
            )
        }
    }

    fun setAmbientMode(ambientSettings: AmbientSettings) {
        areaAdapter.apply {
            setAmbientMode(ambientSettings.isAmbientMode, ambientSettings.isLowBitAmbient)
            notifyDataSetChanged()
        }

        recyclerGrid.setBackgroundResource(
            if (ambientSettings.isAmbientMode) android.R.color.black else android.R.color.transparent
        )
    }
}
