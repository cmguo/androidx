/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.foundation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapAndPress
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * [CompositionLocal] used to pass [ScrollbarStyle] down the tree.
 * This value is typically set in some "Theme" composable function
 * (DesktopTheme, MaterialTheme)
 */
val LocalScrollbarStyle = staticCompositionLocalOf { defaultScrollbarStyle() }

/**
 * Defines visual style of scrollbars (thickness, shapes, colors, etc).
 * Can be passed as a parameter of scrollbar through [LocalScrollbarStyle]
 */
@Immutable
data class ScrollbarStyle(
    val minimalHeight: Dp,
    val thickness: Dp,
    val shape: Shape,
    val hoverDurationMillis: Int,
    val unhoverColor: Color,
    val hoverColor: Color
)

/**
 * Simple default [ScrollbarStyle] without hover effects and without applying MaterialTheme.
 */
fun defaultScrollbarStyle() = ScrollbarStyle(
    minimalHeight = 16.dp,
    thickness = 8.dp,
    shape = RectangleShape,
    hoverDurationMillis = 0,
    unhoverColor = Color.Black.copy(alpha = 0.12f),
    hoverColor = Color.Black.copy(alpha = 0.12f)
)

/**
 * Vertical scrollbar that can be attached to some scrollable
 * component (ScrollableColumn, LazyColumn) and share common state with it.
 *
 * Can be placed independently.
 *
 * Example:
 *     val state = rememberScrollState(0f)
 *
 *     Box(Modifier.fillMaxSize()) {
 *         Box(modifier = Modifier.verticalScroll(state)) {
 *             ...
 *         }
 *
 *         VerticalScrollbar(
 *             Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
 *             rememberScrollbarAdapter(state)
 *         )
 *     }
 *
 * @param adapter [ScrollbarAdapter] that will be used to communicate with scrollable component
 * @param modifier the modifier to apply to this layout
 * @param style [ScrollbarStyle] to define visual style of scrollbar
 * @param interactionSource [MutableInteractionSource] that will be used to dispatch
 * [DragInteraction.Start] when this Scrollbar is being dragged.
 */
@Composable
fun VerticalScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
    style: ScrollbarStyle = LocalScrollbarStyle.current,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) = Scrollbar(
    adapter,
    modifier,
    style,
    interactionSource,
    isVertical = true
)

/**
 * Horizontal scrollbar that can be attached to some scrollable
 * component (Modifier.verticalScroll(), LazyRow) and share common state with it.
 *
 * Can be placed independently.
 *
 * Example:
 *     val state = rememberScrollState(0f)
 *
 *     Box(Modifier.fillMaxSize()) {
 *         Box(modifier = Modifier.verticalScroll(state)) {
 *             ...
 *         }
 *
 *         HorizontalScrollbar(
 *             Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
 *             rememberScrollbarAdapter(state)
 *         )
 *     }
 *
 * @param adapter [ScrollbarAdapter] that will be used to communicate with scrollable component
 * @param modifier the modifier to apply to this layout
 * @param style [ScrollbarStyle] to define visual style of scrollbar
 * @param interactionSource [MutableInteractionSource] that will be used to dispatch
 * [DragInteraction.Start] when this Scrollbar is being dragged.
 */
@Composable
fun HorizontalScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
    style: ScrollbarStyle = LocalScrollbarStyle.current,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) = Scrollbar(
    adapter,
    modifier,
    style,
    interactionSource,
    isVertical = false
)

// TODO(demin): do we need to stop dragging if cursor is beyond constraints?
// TODO(demin): add Interaction.Hovered to interactionSource
@Composable
private fun Scrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
    style: ScrollbarStyle,
    interactionSource: MutableInteractionSource,
    isVertical: Boolean
) = with(LocalDensity.current) {
    val dragInteraction = remember { mutableStateOf<DragInteraction.Start?>(null) }
    DisposableEffect(interactionSource) {
        onDispose {
            dragInteraction.value?.let { interaction ->
                interactionSource.tryEmit(DragInteraction.Cancel(interaction))
                dragInteraction.value = null
            }
        }
    }

    var containerSize by remember { mutableStateOf(0) }
    var isHovered by remember { mutableStateOf(false) }

    val isHighlighted by remember {
        derivedStateOf {
            isHovered || dragInteraction.value is DragInteraction.Start
        }
    }

    val minimalHeight = style.minimalHeight.toPx()
    val sliderAdapter = remember(adapter, containerSize, minimalHeight) {
        SliderAdapter(adapter, containerSize, minimalHeight)
    }

    val scrollThickness = style.thickness.roundToPx()
    val measurePolicy = if (isVertical) {
        remember(sliderAdapter, scrollThickness) {
            verticalMeasurePolicy(sliderAdapter, { containerSize = it }, scrollThickness)
        }
    } else {
        remember(sliderAdapter, scrollThickness) {
            horizontalMeasurePolicy(sliderAdapter, { containerSize = it }, scrollThickness)
        }
    }

    val color by animateColorAsState(
        if (isHighlighted) style.hoverColor else style.unhoverColor,
        animationSpec = TweenSpec(durationMillis = style.hoverDurationMillis)
    )

    val isVisible = sliderAdapter.size < containerSize

    Layout(
        {
            Box(
                Modifier
                    .background(if (isVisible) color else Color.Transparent, style.shape)
                    .scrollbarDrag(interactionSource, dragInteraction) { offset ->
                        sliderAdapter.position += if (isVertical) offset.y else offset.x
                    }
            )
        },
        modifier
            .pointerMoveFilter(
                onExit = { isHovered = false; false },
                onEnter = { isHovered = true; false }
            )
            .scrollOnPressOutsideSlider(isVertical, sliderAdapter, adapter, containerSize),
        measurePolicy
    )
}

private fun Modifier.scrollbarDrag(
    interactionSource: MutableInteractionSource,
    draggedInteraction: MutableState<DragInteraction.Start?>,
    onDelta: (Offset) -> Unit
): Modifier = composed {
    val currentInteractionSource by rememberUpdatedState(interactionSource)
    val currentDraggedInteraction by rememberUpdatedState(draggedInteraction)
    val currentOnDelta by rememberUpdatedState(onDelta)
    pointerInput(Unit) {
        forEachGesture {
            awaitPointerEventScope {
                val down = awaitFirstDown(requireUnconsumed = false)
                val interaction = DragInteraction.Start()
                currentInteractionSource.tryEmit(interaction)
                currentDraggedInteraction.value = interaction
                val isSuccess = drag(down.id) { change ->
                    currentOnDelta.invoke(change.positionChange())
                    change.consumePositionChange()
                }
                val finishInteraction = if (isSuccess) {
                    DragInteraction.Stop(interaction)
                } else {
                    DragInteraction.Cancel(interaction)
                }
                currentInteractionSource.tryEmit(finishInteraction)
                currentDraggedInteraction.value = null
            }
        }
    }
}

private fun Modifier.scrollOnPressOutsideSlider(
    isVertical: Boolean,
    sliderAdapter: SliderAdapter,
    scrollbarAdapter: ScrollbarAdapter,
    containerSize: Int
) = composed {
    var targetOffset: Offset? by remember { mutableStateOf(null) }

    if (targetOffset != null) {
        val targetPosition = if (isVertical) targetOffset!!.y else targetOffset!!.x

        LaunchedEffect(targetPosition) {
            var delay = PressTimeoutMillis * 3
            while (targetPosition !in sliderAdapter.bounds) {
                val oldSign = sign(targetPosition - sliderAdapter.position)
                scrollbarAdapter.scrollTo(
                    containerSize,
                    scrollbarAdapter.scrollOffset + oldSign * containerSize
                )
                val newSign = sign(targetPosition - sliderAdapter.position)

                if (oldSign != newSign) {
                    break
                }

                delay(delay)
                delay = PressTimeoutMillis
            }
        }
    }
    Modifier.pointerInput(Unit) {
        detectTapAndPress(
            onPress = { offset ->
                targetOffset = offset
                tryAwaitRelease()
                targetOffset = null
            },
            onTap = {}
        )
    }
}

/**
 * Create and [remember] [ScrollbarAdapter] for scrollable container and current instance of
 * [scrollState]
 */
@Composable
fun rememberScrollbarAdapter(
    scrollState: ScrollState
): ScrollbarAdapter = remember(scrollState) {
    ScrollbarAdapter(scrollState)
}

/**
 * Create and [remember] [ScrollbarAdapter] for lazy scrollable container and current instance of
 * [scrollState] and item configuration
 */
@ExperimentalFoundationApi
@Composable
fun rememberScrollbarAdapter(
    scrollState: LazyListState,
    itemCount: Int,
    averageItemSize: Dp
): ScrollbarAdapter {
    val averageItemSizePx = with(LocalDensity.current) {
        averageItemSize.toPx()
    }
    return remember(scrollState, itemCount, averageItemSizePx) {
        ScrollbarAdapter(scrollState, itemCount, averageItemSizePx)
    }
}

/**
 * ScrollbarAdapter for Modifier.verticalScroll and Modifier.horizontalScroll
 *
 * [scrollState] is instance of [ScrollState] which is used by scrollable component
 *
 * Example:
 *     val state = rememberScrollState(0f)
 *
 *     Box(Modifier.fillMaxSize()) {
 *         Box(modifier = Modifier.verticalScroll(state)) {
 *             ...
 *         }
 *
 *         VerticalScrollbar(
 *             Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
 *             rememberScrollbarAdapter(state)
 *         )
 *     }
 */
fun ScrollbarAdapter(
    scrollState: ScrollState
): ScrollbarAdapter = ScrollableScrollbarAdapter(scrollState)

private class ScrollableScrollbarAdapter(
    private val scrollState: ScrollState
) : ScrollbarAdapter {
    override val scrollOffset: Float get() = scrollState.value.toFloat()

    override suspend fun scrollTo(containerSize: Int, scrollOffset: Float) {
        scrollState.scrollTo(scrollOffset.roundToInt())
    }

    override fun maxScrollOffset(containerSize: Int) =
        scrollState.maxValue.toFloat()
}

// TODO(demin): if item height is different then slider will have wrong
//  position when we dragging it (we can drag it to the beginning, but content will not be at the
//  beginning). We can implement adaptive scrollbar height after b/170472532

/**
 * Experimental ScrollbarAdapter for lazy lists. Doesn't work stable with non-fixed item height.
 *
 * [scrollState] is instance of [LazyListState] which is used by scrollable component
 *
 * Scrollbar size and position will be calculated by passed [itemCount] and [averageItemSize]
 *
 * Example:
 *     Box(Modifier.fillMaxSize()) {
 *         val state = rememberLazyListState()
 *         val itemCount = 100
 *         val itemHeight = 20.dp
 *
 *         LazyColumn(state = state) {
 *             ...
 *         }
 *
 *         VerticalScrollbar(
 *             Modifier.align(Alignment.CenterEnd),
 *             rememberScrollbarAdapter(state, itemCount, itemHeight)
 *         )
 *     }
 */
@ExperimentalFoundationApi
fun ScrollbarAdapter(
    scrollState: LazyListState,
    itemCount: Int,
    averageItemSize: Float
): ScrollbarAdapter = LazyScrollbarAdapter(
    scrollState, itemCount, averageItemSize
)

private class LazyScrollbarAdapter(
    private val scrollState: LazyListState,
    private val itemCount: Int,
    private val averageItemSize: Float
) : ScrollbarAdapter {
    init {
        require(itemCount >= 0f) { "itemCount should be non-negative ($itemCount)" }
        require(averageItemSize > 0f) { "averageItemSize should be positive ($averageItemSize)" }
    }

    override val scrollOffset: Float
        get() = scrollState.firstVisibleItemIndex * averageItemSize +
            scrollState.firstVisibleItemScrollOffset

    override suspend fun scrollTo(containerSize: Int, scrollOffset: Float) {
        // In case of very big values, we can catch an overflow, so convert values to double and
        // coerce them
//        val averageItemSize = 26.000002f
//        val scrollOffsetCoerced = 2.54490608E8.toFloat()
//        val index = (scrollOffsetCoerced / averageItemSize).toInt() // 9788100
//        val offset = (scrollOffsetCoerced - index * averageItemSize) // -16.0
//        println(offset)

        val maximumValue = maxScrollOffset(containerSize).toDouble()
        val scrollOffsetCoerced = scrollOffset.toDouble().coerceIn(0.0, maximumValue)
        val averageItemSize = averageItemSize.toDouble()

        val index = (scrollOffsetCoerced / averageItemSize)
            .toInt()
            .coerceAtLeast(0)
            .coerceAtMost(itemCount - 1)

        val offset = (scrollOffsetCoerced - index * averageItemSize)
            .toInt()
            .coerceAtLeast(0)

        scrollState.scrollToItem(index = index, scrollOffset = offset)
    }

    override fun maxScrollOffset(containerSize: Int) =
        averageItemSize * itemCount - containerSize
}

/**
 * Defines how to scroll the scrollable component
 */
interface ScrollbarAdapter {
    /**
     * Scroll offset of the content inside the scrollable component.
     * Offset "100" means that the content is scrolled by 100 pixels from the start.
     */
    val scrollOffset: Float

    /**
     * Instantly jump to [scrollOffset] in pixels
     *
     * @param containerSize size of the scrollable container
     *  (for example, it is height of ScrollableColumn if we use VerticalScrollbar)
     * @param scrollOffset target value in pixels to jump to,
     *  value will be coerced to 0..maxScrollOffset
     */
    suspend fun scrollTo(containerSize: Int, scrollOffset: Float)

    /**
     * Maximum scroll offset of the content inside the scrollable component
     *
     * @param containerSize size of the scrollable component
     *  (for example, it is height of ScrollableColumn if we use VerticalScrollbar)
     */
    fun maxScrollOffset(containerSize: Int): Float
}

private class SliderAdapter(
    val adapter: ScrollbarAdapter,
    val containerSize: Int,
    val minHeight: Float
) {
    private val contentSize get() = adapter.maxScrollOffset(containerSize) + containerSize
    private val visiblePart get() = containerSize.toFloat() / contentSize

    val size
        get() = (containerSize * visiblePart)
            .coerceAtLeast(minHeight)
            .coerceAtMost(containerSize.toFloat())

    private val scrollScale: Float
        get() {
            val extraScrollbarSpace = containerSize - size
            val extraContentSpace = contentSize - containerSize
            return if (extraContentSpace == 0f) 1f else extraScrollbarSpace / extraContentSpace
        }

    var position: Float
        get() = scrollScale * adapter.scrollOffset
        set(value) {
            runBlocking {
                adapter.scrollTo(containerSize, value / scrollScale)
            }
        }

    val bounds get() = position..position + size
}

private fun verticalMeasurePolicy(
    sliderAdapter: SliderAdapter,
    setContainerSize: (Int) -> Unit,
    scrollThickness: Int
) = MeasurePolicy { measurables, constraints ->
    setContainerSize(constraints.maxHeight)
    val height = sliderAdapter.size.toInt()
    val placeable = measurables.first().measure(
        Constraints.fixed(
            constraints.constrainWidth(scrollThickness),
            height
        )
    )
    layout(placeable.width, constraints.maxHeight) {
        placeable.place(0, sliderAdapter.position.toInt())
    }
}

private fun horizontalMeasurePolicy(
    sliderAdapter: SliderAdapter,
    setContainerSize: (Int) -> Unit,
    scrollThickness: Int
) = MeasurePolicy { measurables, constraints ->
    setContainerSize(constraints.maxWidth)
    val width = sliderAdapter.size.toInt()
    val placeable = measurables.first().measure(
        Constraints.fixed(
            width,
            constraints.constrainHeight(scrollThickness)
        )
    )
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(sliderAdapter.position.toInt(), 0)
    }
}

/**
 * The time that must elapse before a tap gesture sends onTapDown, if there's
 * any doubt that the gesture is a tap.
 */
private const val PressTimeoutMillis: Long = 100L
