package dev.lucasnlm.antimine.common.level

import dev.lucasnlm.antimine.common.level.database.models.FirstOpen
import dev.lucasnlm.antimine.common.level.database.models.Save
import dev.lucasnlm.antimine.common.level.database.models.SaveStatus
import dev.lucasnlm.antimine.common.level.database.models.Stats
import dev.lucasnlm.antimine.common.level.logic.FlagAssistant
import dev.lucasnlm.antimine.common.level.logic.MinefieldCreator
import dev.lucasnlm.antimine.common.level.logic.MinefieldHandler
import dev.lucasnlm.antimine.common.level.logic.filterNeighborsOf
import dev.lucasnlm.antimine.common.level.models.Area
import dev.lucasnlm.antimine.common.level.models.Difficulty
import dev.lucasnlm.antimine.common.level.models.Minefield
import dev.lucasnlm.antimine.common.level.models.Score
import dev.lucasnlm.antimine.common.level.solver.LimitedBruteForceSolver
import dev.lucasnlm.antimine.core.control.ActionResponse
import dev.lucasnlm.antimine.core.control.GameControl
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

class GameController {
    private val minefield: Minefield
    private val startTime = System.currentTimeMillis()
    private var saveId = 0
    private var firstOpen: FirstOpen = FirstOpen.Unknown
    private var gameControl: GameControl = GameControl.Standard
    private var useQuestionMark = true

    val seed: Long

    private val minefieldCreator: MinefieldCreator
    private var field: List<Area>

    constructor(minefield: Minefield, seed: Long, isRound: Boolean, saveId: Int? = null) {
        this.minefieldCreator = MinefieldCreator(minefield, Random(seed), isRound)
        this.minefield = minefield
        this.seed = seed
        this.saveId = saveId ?: 0

        this.field = minefieldCreator.createEmpty()
    }

    constructor(save: Save) {
        this.minefieldCreator = MinefieldCreator(save.minefield, Random(save.seed), false)
        this.minefield = save.minefield
        this.saveId = save.uid
        this.seed = save.seed
        this.firstOpen = save.firstOpen
        this.field = save.field
    }

    fun field() = field

    fun field(predicate: (Area) -> Boolean) = field.filter(predicate)

    fun mines() = field.filter { it.hasMine }

    fun hasMines() = field.firstOrNull { it.hasMine } != null

    private fun getArea(id: Int) = field.first { it.id == id }

    private fun plantMinesExcept(safeId: Int) {
        val solver = LimitedBruteForceSolver()
        do {
            val useSafeZone = minefield.width > 7 && minefield.height > 9
            field = minefieldCreator.create(safeId, useSafeZone)
            val fieldCopy = field.map { it.copy() }.toMutableList()
            val minefieldHandler = MinefieldHandler(fieldCopy, false)
            minefieldHandler.openAt(safeId, false)
        } while (solver.keepTrying() && !solver.trySolve(minefieldHandler.result().toMutableList()))

        firstOpen = FirstOpen.Position(safeId)
    }

    private fun handleAction(target: Area, actionResponse: ActionResponse?) {
        val mustPlantMines = !hasMines()

        val minefieldHandler: MinefieldHandler

        if (mustPlantMines) {
            plantMinesExcept(target.id)
            minefieldHandler = MinefieldHandler(field.toMutableList(), useQuestionMark)
            minefieldHandler.openAt(target.id, false)
        } else {
            minefieldHandler = MinefieldHandler(field.toMutableList(), useQuestionMark)
            minefieldHandler.turnOffAllHighlighted()

            when (actionResponse) {
                ActionResponse.OpenTile -> {
                    if (target.mark.isNotNone()) {
                        minefieldHandler.removeMarkAt(target.id)
                    } else {
                        minefieldHandler.openAt(target.id, false)
                    }
                }
                ActionResponse.SwitchMark -> {
                    if (!hasMines()) {
                        if (target.mark.isNotNone()) {
                            minefieldHandler.removeMarkAt(target.id)
                        } else {
                            minefieldHandler.openAt(target.id, false)
                        }
                    } else {
                        minefieldHandler.switchMarkAt(target.id)
                    }
                }
                ActionResponse.HighlightNeighbors -> {
                    if (target.minesAround != 0) {
                        minefieldHandler.highlightAt(target.id)
                    }
                }
                ActionResponse.OpenNeighbors -> {
                    minefieldHandler.openOrFlagNeighborsOf(target.id)
                }
            }
        }

        field = minefieldHandler.result()
    }

    fun singleClick(index: Int) = flow {
        val target = getArea(index)
        val action = if (target.isCovered) gameControl.onCovered.singleClick else gameControl.onOpen.singleClick
        action?.let {
            handleAction(target, action)
            emit(action)
        }
    }

    fun doubleClick(index: Int) = flow {
        val target = getArea(index)
        val action = if (target.isCovered) gameControl.onCovered.doubleClick else gameControl.onOpen.doubleClick
        action?.let {
            handleAction(target, action)
            emit(action)
        }
    }

    fun longPress(index: Int) = flow {
        val target = getArea(index)
        val action = if (target.isCovered) gameControl.onCovered.longPress else gameControl.onOpen.longPress
        action?.let {
            handleAction(target, action)
            emit(action)
        }
    }

    fun runFlagAssistant() {
        field = FlagAssistant(field.toMutableList()).run {
            runFlagAssistant()
            result()
        }
    }

    fun getScore() = Score(
        mines().count { !it.mistake && it.mark.isFlag() },
        getMinesCount(),
        field.count()
    )

    fun getMinesCount() = mines().count()

    fun showAllMines() {
        field = MinefieldHandler(field.toMutableList(), false).run {
            showAllMines()
            result()
        }
    }

    fun findExplodedMine() = mines().firstOrNull { it.mistake }

    fun takeExplosionRadius(target: Area): List<Area> =
        mines().filter { it.isCovered && it.mark.isNone() }.sortedBy {
            val dx1 = (it.posX - target.posX)
            val dy1 = (it.posY - target.posY)
            dx1 * dx1 + dy1 * dy1
        }

    fun revealArea(id: Int) {
        field = MinefieldHandler(field.toMutableList(), false).run {
            openAt(id, passive = true, openNeighbors = false)
            result()
        }
    }

    fun flagAllMines() {
        field = MinefieldHandler(field.toMutableList(), false).run {
            flagAllMines()
            result()
        }
    }

    fun showWrongFlags() {
        field = field.map {
            if (!it.hasMine && it.mark.isFlag()) {
                it.copy(mistake = true)
            } else {
                it
            }
        }
    }

    fun revealAllEmptyAreas() {
        field = MinefieldHandler(field.toMutableList(), false).run {
            revealAllEmptyAreas()
            result()
        }
    }

    fun hasAnyMineExploded(): Boolean = mines().firstOrNull { it.mistake } != null

    fun hasFlaggedAllMines(): Boolean = rightFlags() == minefield.mines

    fun hasIsolatedAllMines() =
        mines().map {
            val neighbors = field.filterNeighborsOf(it)
            val neighborsCount = neighbors.count()
            val isolatedNeighborsCount = neighbors.count { neighbor ->
                !neighbor.isCovered || neighbor.hasMine
            }
            neighborsCount != isolatedNeighborsCount
        }.count { it } == 0

    private fun rightFlags() = mines().count { it.mark.isFlag() }

    fun checkVictory(): Boolean =
        hasMines() && hasIsolatedAllMines() && !hasAnyMineExploded()

    fun isGameOver(): Boolean =
        checkVictory() || hasAnyMineExploded()

    fun remainingMines(): Int {
        val flagsCount = field.count { it.mark.isFlag() }
        val minesCount = mines().count()
        return (minesCount - flagsCount).coerceAtLeast(0)
    }

    fun getSaveState(duration: Long, difficulty: Difficulty): Save {
        val saveStatus: SaveStatus = when {
            checkVictory() -> SaveStatus.VICTORY
            hasAnyMineExploded() -> SaveStatus.DEFEAT
            else -> SaveStatus.ON_GOING
        }
        return Save(
            saveId,
            seed,
            startTime,
            duration,
            minefield,
            difficulty,
            firstOpen,
            saveStatus,
            field.toList()
        )
    }

    fun getStats(duration: Long): Stats? {
        val gameStatus: SaveStatus = when {
            checkVictory() -> SaveStatus.VICTORY
            hasAnyMineExploded() -> SaveStatus.DEFEAT
            else -> SaveStatus.ON_GOING
        }
        return if (gameStatus == SaveStatus.ON_GOING) {
            null
        } else {
            Stats(
                0,
                duration,
                getMinesCount(),
                if (gameStatus == SaveStatus.VICTORY) 1 else 0,
                minefield.width,
                minefield.height,
                mines().count { !it.isCovered }
            )
        }
    }

    fun setCurrentSaveId(id: Int) {
        this.saveId = id.coerceAtLeast(0)
    }

    fun updateGameControl(newGameControl: GameControl) {
        this.gameControl = newGameControl
    }

    fun useQuestionMark(useQuestionMark: Boolean) {
        this.useQuestionMark = useQuestionMark
    }
}
