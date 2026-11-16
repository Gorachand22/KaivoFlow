package com.kaivoflow.core.expressions.assist

import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap

internal fun <K, V> immutableMap(m: Map<K, V>): ImmutableMap<K, V> = m.toImmutableMap()
