package com.kaivoflow.feature.canvas.sheets

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaivoflow.core.design.KaivoIcons
import com.kaivoflow.core.design.KaivoShapes
import com.kaivoflow.core.design.KaivoTheme
import com.kaivoflow.core.design.SheetDragHandle
import com.kaivoflow.core.design.StepMedallion
import com.kaivoflow.core.model.CategoryTree
import com.kaivoflow.core.model.Lifecycle
import com.kaivoflow.core.model.NodeSpec
import com.kaivoflow.core.model.TopCategory
import com.kaivoflow.feature.canvas.picker.CategoryIcons
import com.kaivoflow.feature.canvas.picker.CategoryPage
import com.kaivoflow.feature.canvas.picker.PickerLogic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Step Picker: in-canvas bottom sheet over the category tree (taxonomy spec section 7).
 *
 * Home: Recent, Favourites, then a tile per top-level category. A tile opens a category page with
 * subcategory sections. Typing searches every Step by name, keyword, description and category.
 * While the Flow has no Spark the sheet shows Sparks only. Press and hold a Step, then drag, to
 * place it on the canvas; a plain tap adds it at the viewport centre ([onPick]).
 *
 * All ranking, grouping and recents logic is in [PickerLogic] (pure, unit tested).
 */
@Composable
fun StepPickerSheet(
    specs: List<NodeSpec>,
    onSearch: (String) -> Unit,
    onPick: (NodeSpec) -> Unit,
    onDismiss: () -> Unit,
    hasTrigger: Boolean,
    onDropOnCanvas: ((NodeSpec, Offset) -> Unit)? = null,
) {
    val colors = KaivoTheme.colors
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val prefs = remember(context) { PickerPrefs(context) }

    var rawQuery by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var pageKey by remember { mutableStateOf<String?>(null) }
    var addAnotherSpark by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf(prefs.recents()) }
    var favourites by remember { mutableStateOf(prefs.favourites()) }
    var draggingSpec by remember { mutableStateOf<NodeSpec?>(null) }
    var dragScreenOffset by remember { mutableStateOf(Offset.Zero) }
    val homeListState = rememberLazyListState()

    val sparksOnly = !hasTrigger
    val offerable = remember(specs) { PickerLogic.offerable(specs) }
    // What the user may add right now. Search widens this to Sparks when a Spark clearly matches.
    val steps = remember(offerable, hasTrigger, addAnotherSpark) {
        PickerLogic.available(offerable, hasSpark = hasTrigger, sparksOnly = sparksOnly, includeSparks = addAnotherSpark)
    }
    val searchPool = remember(offerable, sparksOnly) {
        if (sparksOnly) offerable.filter(PickerLogic::isSpark) else offerable
    }
    val index = remember(searchPool) { PickerLogic.SearchIndex(searchPool) }
    val hits = remember(index, query) { index.search(query) }
    val counts = remember(steps) { PickerLogic.counts(steps) }
    val searchTops = remember(searchPool) { PickerLogic.visibleTops(searchPool) }
    val searchCounts = remember(searchPool) { PickerLogic.counts(searchPool) }
    val homeTops = remember(steps) { PickerLogic.visibleTops(steps) }
    val recentSpecs = remember(recents, steps) { PickerLogic.resolve(recents, steps) }
    val favouriteSpecs = remember(favourites, steps) { PickerLogic.resolve(favourites, steps) }
    val favouriteIds = remember(favourites) { favourites.toSet() }

    // Search is debounced about 120 ms; blank clears at once.
    LaunchedEffect(rawQuery) {
        if (rawQuery.isBlank()) {
            query = ""
        } else {
            delay(120)
            query = rawQuery
        }
        onSearch(query)
    }

    val searching = query.isNotBlank()
    val activeTop: String? = if (sparksOnly) PickerLogic.SPARK else pageKey
    val canGoBack = !sparksOnly && pageKey != null && !searching

    fun recordPick(spec: NodeSpec) {
        recents = PickerLogic.pushRecent(recents, spec.id)
        prefs.saveRecents(recents)
    }

    val pick: (NodeSpec) -> Unit = { spec ->
        recordPick(spec)
        onPick(spec)
    }
    val drop: (NodeSpec, Offset) -> Unit = { spec, at ->
        recordPick(spec)
        if (onDropOnCanvas != null) onDropOnCanvas(spec, at) else onPick(spec)
    }
    val toggleFavourite: (NodeSpec) -> Unit = { spec ->
        favourites = PickerLogic.toggleFavourite(favourites, spec.id)
        prefs.saveFavourites(favourites)
    }
    val goBack = {
        pageKey = null
        addAnotherSpark = false
    }
    val gestures = DragGestures(
        onLift = { spec, at ->
            draggingSpec = spec
            dragScreenOffset = at
        },
        onMove = { at -> dragScreenOffset = at },
        onDrop = {
            val current = draggingSpec
            val at = dragScreenOffset
            draggingSpec = null
            if (current != null) drop(current, at)
        },
        onCancel = { draggingSpec = null },
    )

    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val contentMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val dragging = draggingSpec != null
    val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    Box(Modifier.fillMaxSize()) {
        // Scrim
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.canvasVoid.copy(alpha = if (dragging) 0.15f else 0.6f))
                .clickable(enabled = !dragging, onClickLabel = "Close Step picker", onClick = onDismiss),
        )

        // Slide-up sheet, anchored to the bottom edge so every control is within thumb reach.
        AnimatedVisibility(
            visibleState = visibleState,
            enter = if (reduceMotion) EnterTransition.None else slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(sheetShape)
                    .background(colors.surfaceAtelier.copy(alpha = if (dragging) 0.35f else 0.98f))
                    .border(1.dp, colors.hairline, sheetShape)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SheetDragHandle()
                }

                // Header: back (when inside a category), title or breadcrumb, close.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (canGoBack) {
                        PickerIconButton("back", "Back to all categories", onClick = goBack)
                    }
                    val topLabel = activeTop?.let { key -> CategoryTree.top.firstOrNull { it.key == key }?.label }
                    val title = when {
                        searching -> "Search results"
                        sparksOnly -> "Start with a Spark"
                        topLabel != null -> "Steps / $topLabel"
                        else -> "Add a Step"
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = if (canGoBack) 0.dp else 4.dp)
                            .semantics { heading() },
                    )
                    PickerIconButton("close", "Close Step picker", onClick = onDismiss)
                }
                Text(
                    if (sparksOnly) {
                        "A Spark is what begins your Flow. Tap one to add it."
                    } else {
                        "Tap to add. Press and hold, then drag, to place on the canvas."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(10.dp))

                SearchField(
                    value = rawQuery,
                    onValueChange = { rawQuery = it },
                    onClear = { rawQuery = "" },
                    focusManager = focusManager,
                )
                Spacer(Modifier.height(10.dp))

                Box(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = contentMaxHeight)) {
                    when {
                        searching -> SearchResults(
                            query = query,
                            hits = hits.map { it.spec },
                            tops = searchTops,
                            counts = searchCounts,
                            favouriteIds = favouriteIds,
                            gestures = gestures,
                            onPick = pick,
                            onToggleFavourite = toggleFavourite,
                            onOpenCategory = { key ->
                                rawQuery = ""
                                query = ""
                                pageKey = key
                                addAnotherSpark = key == PickerLogic.SPARK
                            },
                        )

                        activeTop != null -> {
                            val page = remember(steps, activeTop) { PickerLogic.page(steps, activeTop) }
                            if (page == null || (page.sections.isEmpty() && page.legacy.isEmpty())) {
                                EmptyMessage("Nothing here yet.", "Steps for this category will appear as they are added.")
                            } else {
                                CategoryPageContent(
                                    page = page,
                                    favouriteIds = favouriteIds,
                                    highlightId = if (sparksOnly) "trigger.manual" else null,
                                    gestures = gestures,
                                    onPick = pick,
                                    onToggleFavourite = toggleFavourite,
                                )
                            }
                        }

                        steps.isEmpty() -> EmptyMessage(
                            "No Steps available.",
                            "Nothing can be added to this Flow right now.",
                        )

                        else -> HomeContent(
                            listState = homeListState,
                            recents = recentSpecs,
                            favourites = favouriteSpecs,
                            tops = homeTops,
                            counts = counts,
                            offerSparkChip = hasTrigger && offerable.any(PickerLogic::isSpark),
                            gestures = gestures,
                            onPick = pick,
                            onOpenCategory = { key -> pageKey = key },
                            onAddAnotherSpark = {
                                addAnotherSpark = true
                                pageKey = PickerLogic.SPARK
                            },
                        )
                    }
                }
            }
        }

        // Fullscreen drag preview floating freely across the entire canvas.
        draggingSpec?.let { dragged ->
            val accent = colors.categoryColor(dragged.category)
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (dragScreenOffset.x - 110.dp.roundToPx()).toInt(),
                            (dragScreenOffset.y - 42.dp.roundToPx()).toInt(),
                        )
                    }
                    .width(220.dp)
                    .clip(KaivoShapes.stepCard)
                    .background(colors.surfaceCard)
                    .border(2.dp, colors.royalGold, KaivoShapes.stepCard)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepMedallion(iconKey = dragged.iconKey, tint = accent, size = 36.dp, iconSize = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(dragged.title, style = MaterialTheme.typography.titleSmall, color = colors.inkPrimary, maxLines = 1)
                        Text(dragged.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ persistence

/** Recent and favourite Step ids, per device, in the app's SharedPreferences pattern (see core/work). */
private class PickerPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("kaivo.picker", Context.MODE_PRIVATE)

    fun recents(): List<String> = PickerLogic.decode(prefs.getString(KEY_RECENT, null))
    fun favourites(): List<String> = PickerLogic.decode(prefs.getString(KEY_FAVOURITES, null))
    fun saveRecents(ids: List<String>) { prefs.edit().putString(KEY_RECENT, PickerLogic.encode(ids)).apply() }
    fun saveFavourites(ids: List<String>) { prefs.edit().putString(KEY_FAVOURITES, PickerLogic.encode(ids)).apply() }

    private companion object {
        const val KEY_RECENT = "recent_ids"
        const val KEY_FAVOURITES = "favourite_ids"
    }
}

// ------------------------------------------------------------------ drag to place

/** Drag callbacks shared by every Step row, card and result in the sheet. */
private class DragGestures(
    val onLift: (NodeSpec, Offset) -> Unit,
    val onMove: (Offset) -> Unit,
    val onDrop: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * Tap adds the Step. Press and hold lifts it (haptic), then dragging moves a preview and releasing
 * drops it. A quick swipe is left to the list, so lists stay scrollable.
 */
@Composable
private fun Modifier.stepGesture(spec: NodeSpec, gestures: DragGestures, onTap: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val latestGestures by rememberUpdatedState(gestures)
    return this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .clickable(onClickLabel = "Add ${spec.title}", onClick = onTap)
        .pointerInput(spec.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                longPress.consume()
                var at = origin + longPress.position
                latestGestures.onLift(spec, at)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        latestGestures.onCancel()
                        return@awaitEachGesture
                    }
                    if (!change.pressed) {
                        change.consume()
                        latestGestures.onDrop()
                        return@awaitEachGesture
                    }
                    at += change.position - change.previousPosition
                    change.consume()
                    latestGestures.onMove(at)
                }
            }
        }
}

// ------------------------------------------------------------------ search field

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    focusManager: FocusManager,
) {
    val colors = KaivoTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { contentDescription = "Search Steps" },
        placeholder = { Text("Search Steps, apps, actions") },
        leadingIcon = { Icon(KaivoIcons.icon("search"), null, tint = colors.inkSecondary) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                PickerIconButton("close", "Clear search", onClick = onClear)
            }
        },
        singleLine = true,
        shape = KaivoShapes.chip,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

// ------------------------------------------------------------------ home

@Composable
private fun HomeContent(
    listState: LazyListState,
    recents: List<NodeSpec>,
    favourites: List<NodeSpec>,
    tops: List<TopCategory>,
    counts: Map<String, Int>,
    offerSparkChip: Boolean,
    gestures: DragGestures,
    onPick: (NodeSpec) -> Unit,
    onOpenCategory: (String) -> Unit,
    onAddAnotherSpark: () -> Unit,
) {
    val colors = KaivoTheme.colors
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (recents.isNotEmpty()) {
            item(key = "h:recent") { SectionHeader("Recent") }
            item(key = "r:recent") { CompactRow(recents, "recent", gestures, onPick) }
        }
        item(key = "h:fav") { SectionHeader("Favourites") }
        if (favourites.isNotEmpty()) {
            item(key = "r:fav") { CompactRow(favourites, "fav", gestures, onPick) }
        } else {
            item(key = "e:fav") {
                Text(
                    "Tap the bookmark on any Step to keep it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
        item(key = "h:cats") { SectionHeader("Categories") }
        items(tops.chunked(2), key = { pair -> "t:" + pair.joinToString("+") { it.key } }) { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { top ->
                    CategoryTile(
                        top = top,
                        count = counts[top.key] ?: 0,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenCategory(top.key) },
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (offerSparkChip) {
            item(key = "spark-chip") {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    PickerChip("Add another Spark", selected = false, onClick = onAddAnotherSpark)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = KaivoTheme.colors.inkSecondary,
        modifier = Modifier
            .padding(start = 4.dp, top = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun CompactRow(
    list: List<NodeSpec>,
    group: String,
    gestures: DragGestures,
    onPick: (NodeSpec) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { "$group:${it.id}" }) { spec ->
            CompactCard(spec, gestures) { onPick(spec) }
        }
    }
}

@Composable
private fun CompactCard(spec: NodeSpec, gestures: DragGestures, onTap: () -> Unit) {
    val colors = KaivoTheme.colors
    Column(
        Modifier
            .width(128.dp)
            .heightIn(min = 96.dp)
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.stepCard)
            .stepGesture(spec, gestures, onTap)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${spec.title}. ${PickerLogic.breadcrumb(spec)}"
            },
    ) {
        StepMedallion(iconKey = spec.iconKey, tint = colors.categoryColor(spec.category), size = 32.dp, iconSize = 16.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            spec.title,
            style = MaterialTheme.typography.titleSmall,
            color = colors.inkPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CategoryTile(top: TopCategory, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val colors = KaivoTheme.colors
    val countText = if (count == 1) "1 Step" else "$count Steps"
    Column(
        modifier
            .heightIn(min = 96.dp)
            .clip(KaivoShapes.stepCard)
            .background(colors.surfaceCard)
            .border(1.dp, colors.hairline, KaivoShapes.stepCard)
            .clickable(role = Role.Button, onClickLabel = "Open ${top.label}", onClick = onClick)
            .padding(12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${top.label}, $countText" },
    ) {
        StepMedallion(
            iconKey = CategoryIcons.of(top.key),
            tint = colors.categoryColor(top.hue),
            size = 36.dp,
            iconSize = 18.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(top.label, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary, maxLines = 1)
        Text(countText, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary, maxLines = 1)
    }
}

// ------------------------------------------------------------------ category page

private sealed interface PageItem {
    val key: String

    class Header(val sectionKey: String, val label: String) : PageItem {
        override val key get() = "h:$sectionKey"
    }

    class Step(val sectionKey: String, val spec: NodeSpec) : PageItem {
        override val key get() = "s:$sectionKey:${spec.id}"
    }

    class LegacyToggle(val count: Int, val expanded: Boolean) : PageItem {
        override val key get() = "legacy-toggle"
    }

    class LegacyStep(val spec: NodeSpec) : PageItem {
        override val key get() = "l:${spec.id}"
    }
}

@Composable
private fun CategoryPageContent(
    page: CategoryPage,
    favouriteIds: Set<String>,
    highlightId: String?,
    gestures: DragGestures,
    onPick: (NodeSpec) -> Unit,
    onToggleFavourite: (NodeSpec) -> Unit,
) {
    val listState = rememberLazyListState()
    var legacyExpanded by remember(page.top.key) { mutableStateOf(false) }
    val pageItems = remember(page, legacyExpanded) {
        buildList<PageItem> {
            page.sections.forEach { section ->
                val sectionKey = "${section.topKey}/${section.subKey}"
                add(PageItem.Header(sectionKey, section.label))
                section.steps.forEach { add(PageItem.Step(sectionKey, it)) }
            }
            if (page.legacy.isNotEmpty()) {
                add(PageItem.LegacyToggle(page.legacy.size, legacyExpanded))
                if (legacyExpanded) page.legacy.forEach { add(PageItem.LegacyStep(it)) }
            }
        }
    }
    val headerIndexes = remember(pageItems) {
        pageItems.mapIndexedNotNull { i, item -> if (item is PageItem.Header) i to item.label else null }
    }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        if (headerIndexes.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(headerIndexes, key = { it.first }) { (index, label) ->
                    PickerChip(label, selected = false, description = "Jump to $label") {
                        scope.launch { listState.scrollToItem(index) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(pageItems, key = { it.key }) { item ->
                when (item) {
                    is PageItem.Header -> SectionHeader(item.label)
                    is PageItem.Step -> StepRow(
                        spec = item.spec,
                        favourite = item.spec.id in favouriteIds,
                        highlighted = item.spec.id == highlightId,
                        showBreadcrumb = false,
                        gestures = gestures,
                        onPick = onPick,
                        onToggleFavourite = onToggleFavourite,
                    )

                    is PageItem.LegacyToggle -> LegacyToggleRow(item.count, item.expanded) {
                        legacyExpanded = !legacyExpanded
                    }

                    is PageItem.LegacyStep -> StepRow(
                        spec = item.spec,
                        favourite = item.spec.id in favouriteIds,
                        highlighted = false,
                        showBreadcrumb = false,
                        gestures = gestures,
                        onPick = onPick,
                        onToggleFavourite = onToggleFavourite,
                    )
                }
            }
        }
    }
}

@Composable
private fun LegacyToggleRow(count: Int, expanded: Boolean, onClick: () -> Unit) {
    val colors = KaivoTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide Legacy Steps" else "Show Legacy Steps", onClick = onClick)
            .padding(horizontal = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Legacy Steps, $count"
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(KaivoIcons.icon("history"), null, tint = colors.inkMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Legacy ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = colors.inkSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (expanded) "Hide" else "Show",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.royalGold,
        )
    }
}

// ------------------------------------------------------------------ rows

@Composable
private fun StepRow(
    spec: NodeSpec,
    favourite: Boolean,
    highlighted: Boolean,
    showBreadcrumb: Boolean,
    gestures: DragGestures,
    onPick: (NodeSpec) -> Unit,
    onToggleFavourite: (NodeSpec) -> Unit,
) {
    val colors = KaivoTheme.colors
    val accent = colors.categoryColor(spec.category)
    val breadcrumb = PickerLogic.breadcrumb(spec)
    val isSpark = PickerLogic.isSpark(spec)
    val isLegacy = spec.lifecycle == Lifecycle.Legacy
    val isBeta = spec.lifecycle == Lifecycle.Beta
    val needsPermission = spec.requiresSensitivePermission != null
    val description = buildString {
        append(spec.title)
        if (spec.subtitle.isNotBlank()) append(". ").append(spec.subtitle)
        if (breadcrumb.isNotBlank()) append(". ").append(breadcrumb.replace(">", "in"))
        if (isSpark) append(". Spark")
        if (isBeta) append(". Beta")
        if (isLegacy) append(". Legacy")
        if (needsPermission) append(". Needs a permission")
    }
    val shape = KaivoShapes.stepCard
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(colors.surfaceCard)
            .border(
                if (highlighted) 2.dp else 1.dp,
                if (highlighted) colors.royalGold else colors.hairline,
                shape,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .stepGesture(spec, gestures) { onPick(spec) }
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
                .semantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepMedallion(iconKey = spec.iconKey, tint = accent, size = 40.dp, iconSize = 20.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        spec.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isLegacy) colors.inkSecondary else colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isSpark) Badge("Spark", colors.stepTrigger)
                    if (isBeta) Badge("Beta", colors.statusWarning)
                    if (isLegacy) Badge("Legacy", colors.inkMuted)
                    if (needsPermission) {
                        Spacer(Modifier.width(6.dp))
                        Icon(KaivoIcons.icon("lock"), null, tint = colors.inkMuted, modifier = Modifier.size(14.dp))
                    }
                }
                if (spec.subtitle.isNotBlank()) {
                    Text(
                        spec.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showBreadcrumb && breadcrumb.isNotBlank()) {
                    Text(
                        breadcrumb,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        FavouriteButton(spec, favourite, onToggleFavourite)
    }
}

@Composable
private fun FavouriteButton(spec: NodeSpec, favourite: Boolean, onToggle: (NodeSpec) -> Unit) {
    val colors = KaivoTheme.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                role = Role.Button,
                onClickLabel = if (favourite) "Remove ${spec.title} from favourites" else "Add ${spec.title} to favourites",
                onClick = { onToggle(spec) },
            )
            .semantics {
                contentDescription = "Favourite ${spec.title}"
                stateDescription = if (favourite) "Favourited" else "Not favourited"
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            KaivoIcons.icon("bookmark"),
            contentDescription = null,
            tint = if (favourite) colors.royalGold else colors.inkMuted.copy(alpha = 0.6f),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun Badge(text: String, tint: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 6.dp)
            .border(1.dp, tint.copy(alpha = 0.6f), KaivoShapes.pill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// ------------------------------------------------------------------ search results

@Composable
private fun SearchResults(
    query: String,
    hits: List<NodeSpec>,
    tops: List<TopCategory>,
    counts: Map<String, Int>,
    favouriteIds: Set<String>,
    gestures: DragGestures,
    onPick: (NodeSpec) -> Unit,
    onToggleFavourite: (NodeSpec) -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    if (hits.isEmpty()) {
        val nearest = remember(query, tops, counts) { PickerLogic.nearestCategories(query, tops, counts) }
        Column(
            Modifier.fillMaxWidth().heightIn(min = 240.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EmptyMessage("Nothing matches \"$query\"", "Check the spelling, or browse a category.")
            if (nearest.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(nearest, key = { it.key }) { top ->
                        PickerChip(top.label, selected = false, description = "Open ${top.label}") {
                            onOpenCategory(top.key)
                        }
                    }
                }
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(hits, key = { it.id }) { spec ->
            StepRow(
                spec = spec,
                favourite = spec.id in favouriteIds,
                highlighted = false,
                showBreadcrumb = true,
                gestures = gestures,
                onPick = onPick,
                onToggleFavourite = onToggleFavourite,
            )
        }
    }
}

// ------------------------------------------------------------------ shared bits

@Composable
private fun EmptyMessage(title: String, body: String) {
    val colors = KaivoTheme.colors
    Column(
        Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(KaivoIcons.icon("search"), null, tint = colors.inkMuted, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
    }
}

@Composable
private fun PickerIconButton(iconKey: String, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            KaivoIcons.icon(iconKey),
            contentDescription = description,
            tint = KaivoTheme.colors.inkSecondary,
        )
    }
}

@Composable
private fun PickerChip(
    label: String,
    selected: Boolean,
    description: String = label,
    onClick: () -> Unit,
) {
    val colors = KaivoTheme.colors
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (selected) colors.royalGold.copy(alpha = 0.18f) else colors.surfaceElevated.copy(alpha = 0.6f))
            .border(1.dp, if (selected) colors.royalGold.copy(alpha = 0.5f) else colors.hairline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.royalGold else colors.inkSecondary,
            maxLines = 1,
        )
    }
}
