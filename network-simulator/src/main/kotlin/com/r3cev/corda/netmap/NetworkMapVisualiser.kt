/*
 * Copyright 2015 Distributed Ledger Group LLC.  Distributed as Licensed Company IP to DLG Group Members
 * pursuant to the August 7, 2015 Advisory Services Agreement and subject to the Company IP License terms
 * set forth therein.
 *
 * All other rights reserved.
 */

package com.r3cev.corda.netmap

import com.r3corda.core.messaging.SingleMessageRecipient
import com.r3corda.core.then
import com.r3corda.core.utilities.ProgressTracker
import com.r3corda.testing.node.InMemoryMessagingNetwork
import com.r3corda.testing.node.MockNetwork
import com.r3corda.simulation.IRSSimulation
import com.r3corda.simulation.Simulation
import com.r3corda.node.services.network.NetworkMapService
import javafx.animation.*
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.value.WritableValue
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Line
import javafx.scene.shape.Polygon
import javafx.stage.Stage
import javafx.util.Duration
import rx.Scheduler
import rx.schedulers.Schedulers
import java.nio.file.Files
import java.nio.file.Paths
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.system.exitProcess
import com.r3cev.corda.netmap.VisualiserViewModel.Style

fun <T : Any> WritableValue<T>.keyValue(endValue: T, interpolator: Interpolator = Interpolator.EASE_OUT) = KeyValue(this, endValue, interpolator)

// TODO: This code is all horribly ugly. Refactor to use TornadoFX to clean it up.

class NetworkMapVisualiser : Application() {
    enum class NodeType {
        BANK, SERVICE
    }

    enum class RunPauseButtonLabel {
        RUN, PAUSE;

        override fun toString(): String {
            return name.toLowerCase().capitalize()
        }
    }

    sealed class RunningPausedState {
        class Running(val tickTimer: TimerTask): RunningPausedState()
        class Paused(): RunningPausedState()

        val buttonLabel: RunPauseButtonLabel
            get() {
                return when (this) {
                    is RunningPausedState.Running -> RunPauseButtonLabel.PAUSE
                    is RunningPausedState.Paused -> RunPauseButtonLabel.RUN
                }
            }
    }

    private val view = VisualiserView()
    private val viewModel = VisualiserViewModel()

    val timer = Timer()
    val uiThread: Scheduler = Schedulers.from { Platform.runLater(it) }

    override fun start(stage: Stage) {
        viewModel.view = view
        viewModel.presentationMode = "--presentation-mode" in parameters.raw
        buildScene(stage)
        viewModel.displayStyle = if ("--circle" in parameters.raw) { Style.CIRCLE } else { viewModel.displayStyle }

        val simulation = viewModel.simulation
        // Update the white-backgrounded label indicating what protocol step it's up to.
        simulation.allProtocolSteps.observeOn(uiThread).subscribe { step: Pair<Simulation.SimulatedNode, ProgressTracker.Change> ->
            val (node, change) = step
            val label = viewModel.nodesToWidgets[node]!!.statusLabel
            if (change is ProgressTracker.Change.Position) {
                // Fade in the status label if it's our first step.
                if (label.text == "") {
                    with(FadeTransition(Duration(150.0), label)) {
                        fromValue = 0.0
                        toValue = 1.0
                        play()
                    }
                }
                label.text = change.newStep.label
                if (change.newStep == ProgressTracker.DONE && change.tracker == change.tracker.topLevelTracker) {
                    runLater(500, -1) {
                        // Fade out the status label.
                        with(FadeTransition(Duration(750.0), label)) {
                            fromValue = 1.0
                            toValue = 0.0
                            setOnFinished { label.text = "" }
                            play()
                        }
                    }
                }
            } else if (change is ProgressTracker.Change.Rendering) {
                label.text = change.ofStep.label
            }
        }
        // Fire the message bullets between nodes.
        simulation.network.messagingNetwork.sentMessages.observeOn(uiThread).subscribe { msg: InMemoryMessagingNetwork.MessageTransfer ->
            val senderNode: MockNetwork.MockNode = simulation.network.addressToNode(msg.sender.myAddress)
            val destNode: MockNetwork.MockNode = simulation.network.addressToNode(msg.recipients as SingleMessageRecipient)

            if (transferIsInteresting(msg)) {
                viewModel.nodesToWidgets[senderNode]!!.pulseAnim.play()
                viewModel.fireBulletBetweenNodes(senderNode, destNode, "bank", "bank")
            }
        }
        // Pulse all parties in a trade when the trade completes
        simulation.doneSteps.observeOn(uiThread).subscribe { nodes: Collection<Simulation.SimulatedNode> ->
            nodes.forEach { viewModel.nodesToWidgets[it]!!.longPulseAnim.play() }
        }

        stage.setOnCloseRequest { exitProcess(0) }
        //stage.isMaximized = true
        stage.show()
    }

    fun runLater(startAfter: Int, delayBetween: Int, body: () -> Unit) {
        if (delayBetween != -1) {
            timer.scheduleAtFixedRate(startAfter.toLong(), delayBetween.toLong()) {
                Platform.runLater {
                    body()
                }
            }
        } else {
            timer.schedule(startAfter.toLong()) {
                Platform.runLater {
                    body()
                }
            }
        }
    }

    private fun buildScene(stage: Stage) {
        view.stage = stage
        view.setup(viewModel.runningPausedState, viewModel.displayStyle, viewModel.presentationMode)
        bindSidebar()
        bindTopbar()
        viewModel.createNodes()

        // Spacebar advances simulation by one step.
        stage.scene.accelerators[KeyCodeCombination(KeyCode.SPACE)] = Runnable { onNextInvoked() }

        reloadStylesheet(stage)

        stage.focusedProperty().addListener { value, old, new ->
            if (new) {
                reloadStylesheet(stage)
            }
        }
    }

    private fun bindTopbar() {
        view.resetButton.setOnAction({reset()})
        view.nextButton.setOnAction {
            if (!view.simulateInitialisationCheckbox.isSelected && !viewModel.simulation.networkInitialisationFinished.isDone) {
                skipNetworkInitialisation()
            } else {
                onNextInvoked()
            }
        }
        viewModel.simulation.networkInitialisationFinished.then {
            view.simulateInitialisationCheckbox.isVisible = false
        }
        view.runPauseButton.setOnAction {
            val oldRunningPausedState = viewModel.runningPausedState
            val newRunningPausedState = when (oldRunningPausedState) {
                is NetworkMapVisualiser.RunningPausedState.Running -> {
                    oldRunningPausedState.tickTimer.cancel()

                    view.nextButton.isDisable = false
                    view.resetButton.isDisable = false

                    NetworkMapVisualiser.RunningPausedState.Paused()
                }
                is NetworkMapVisualiser.RunningPausedState.Paused -> {
                    val tickTimer = timer.scheduleAtFixedRate(viewModel.stepDuration.toMillis().toLong(), viewModel.stepDuration.toMillis().toLong()) {
                        Platform.runLater {
                            onNextInvoked()
                        }
                    }

                    view.nextButton.isDisable = true
                    view.resetButton.isDisable = true

                    if (!view.simulateInitialisationCheckbox.isSelected && !viewModel.simulation.networkInitialisationFinished.isDone) {
                        skipNetworkInitialisation()
                    }

                    NetworkMapVisualiser.RunningPausedState.Running(tickTimer)
                }
            }

            view.runPauseButton.text = newRunningPausedState.buttonLabel.toString()
            viewModel.runningPausedState = newRunningPausedState
        }
        view.styleChoice.selectionModel.selectedItemProperty()
                .addListener { ov, value, newValue -> viewModel.displayStyle = newValue }
        viewModel.simulation.dateChanges.observeOn(uiThread).subscribe { view.dateLabel.text = it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
    }

    private fun reloadStylesheet(stage: Stage) {
        stage.scene.stylesheets.clear()
        stage.scene.stylesheets.add(NetworkMapVisualiser::class.java.getResource("styles.css").toString())
    }

    private fun bindSidebar() {
        viewModel.simulation.allProtocolSteps.observeOn(uiThread).subscribe { step: Pair<Simulation.SimulatedNode, ProgressTracker.Change> ->
            val (node, change) = step

            if (change is ProgressTracker.Change.Position) {
                val tracker = change.tracker.topLevelTracker
                if (change.newStep == ProgressTracker.DONE) {
                    if (change.tracker == tracker) {
                        // Protocol done; schedule it for removal in a few seconds. We batch them up to make nicer
                        // animations.
                        println("Protocol done for ${node.info.legalIdentity.name}")
                        viewModel.doneTrackers += tracker
                    } else {
                        // Subprotocol is done; ignore it.
                    }
                } else if (!viewModel.trackerBoxes.containsKey(tracker)) {
                    // New protocol started up; add.
                    val extraLabel = viewModel.simulation.extraNodeLabels[node]
                    val label = if (extraLabel != null) "${node.info.legalIdentity.name}: $extraLabel" else node.info.legalIdentity.name
                    val widget = view.buildProgressTrackerWidget(label, tracker.topLevelTracker)
                    bindProgressTracketWidget(tracker.topLevelTracker, widget)
                    println("Added: ${tracker}, ${widget}")
                    viewModel.trackerBoxes[tracker] = widget.vbox
                    view.sidebar.children += widget.vbox
                }
            }
        }

        Timer().scheduleAtFixedRate(0, 500) {
            Platform.runLater {
                for (tracker in viewModel.doneTrackers) {
                    val pane = viewModel.trackerBoxes[tracker]!!
                    // Slide the other tracker widgets up and over this one.
                    val slideProp = SimpleDoubleProperty(0.0)
                    slideProp.addListener { obv -> pane.padding = Insets(0.0, 0.0, slideProp.value, 0.0) }
                    val timeline = Timeline(
                            KeyFrame(Duration(250.0),
                                    KeyValue(pane.opacityProperty(), 0.0),
                                    KeyValue(slideProp, -pane.height - 50.0)  // Subtract the bottom padding gap.
                            )
                    )
                    timeline.setOnFinished {
                        println("Removed: ${tracker}")
                        val vbox = viewModel.trackerBoxes.remove(tracker)
                        view.sidebar.children.remove(vbox)
                    }
                    timeline.play()
                }
                viewModel.doneTrackers.clear()
            }
        }
    }

    private fun bindProgressTracketWidget(tracker: ProgressTracker, widget: TrackerWidget) {
        val allSteps: List<Pair<Int, ProgressTracker.Step>> = tracker.allSteps
        tracker.changes.observeOn(uiThread).subscribe { step: ProgressTracker.Change ->
            val stepHeight = widget.cursorBox.height / allSteps.size
            if (step is ProgressTracker.Change.Position) {
                // Figure out the index of the new step.
                val curStep = allSteps.indexOfFirst { it.second == step.newStep }
                // Animate the cursor to the right place.
                with(TranslateTransition(Duration(350.0), widget.cursor)) {
                    fromY = widget.cursor.translateY
                    toY = (curStep * stepHeight) + 22.5
                    play()
                }
            } else if (step is ProgressTracker.Change.Structural) {
                val new = view.buildProgressTrackerWidget(widget.label.text, tracker)
                val prevWidget = viewModel.trackerBoxes[step.tracker] ?: throw AssertionError("No previous widget for tracker: ${step.tracker}")
                val i = (prevWidget.parent as VBox).children.indexOf(viewModel.trackerBoxes[step.tracker])
                (prevWidget.parent as VBox).children[i] = new.vbox
                viewModel.trackerBoxes[step.tracker] = new.vbox
            }
        }
    }

    var started = false
    private fun startSimulation() {
        if (!started) {
            viewModel.simulation.start()
            started = true
        }
    }

    private fun reset() {
        viewModel.simulation.stop()
        viewModel.simulation = IRSSimulation(true, false, null)
        started = false
        start(view.stage)
    }

    private fun skipNetworkInitialisation() {
        startSimulation()
        while (!viewModel.simulation.networkInitialisationFinished.isDone) {
            iterateSimulation()
        }
    }

    private fun onNextInvoked() {
        if (started) {
            iterateSimulation()
        } else {
            startSimulation()
        }
    }

    private fun iterateSimulation() {
        // Loop until either we ran out of things to do, or we sent an interesting message.
        while (true) {
            val transfer: InMemoryMessagingNetwork.MessageTransfer = viewModel.simulation.iterate() ?: break
            if (transferIsInteresting(transfer))
                break
            else
                System.err.println("skipping boring $transfer")
        }
    }

    private fun transferIsInteresting(transfer: InMemoryMessagingNetwork.MessageTransfer): Boolean {
        // Loopback messages are boring.
        if (transfer.sender.myAddress == transfer.recipients) return false
        // Network map push acknowledgements are boring.
        if (NetworkMapService.PUSH_ACK_PROTOCOL_TOPIC in transfer.message.topicSession.topic) return false

        return true
    }
}

fun main(args: Array<String>) {
    Application.launch(NetworkMapVisualiser::class.java, *args)
}