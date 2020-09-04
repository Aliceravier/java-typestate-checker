package org.checkerframework.checker.mungo.core

import com.sun.tools.javac.code.Type
import org.checkerframework.checker.mungo.typecheck.MungoBottomType
import org.checkerframework.checker.mungo.typecheck.MungoType
import org.checkerframework.checker.mungo.typecheck.MungoTypecheck
import org.checkerframework.checker.mungo.typecheck.MungoUnknownType
import org.checkerframework.checker.mungo.utils.MungoUtils
import org.checkerframework.javacutil.TypesUtils
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.Types

class StoreInfo(val analyzer: Analyzer, val mungoType: MungoType, val type: TypeMirror) {

  constructor(prevInfo: StoreInfo, newType: MungoType) : this(prevInfo.analyzer, newType, prevInfo.type)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is StoreInfo) return false
    if (mungoType != other.mungoType) return false
    return (type === other.type || analyzer.utils.isSameType(type, other.type))
  }

  override fun hashCode(): Int {
    return mungoType.hashCode()
  }

  override fun toString(): String {
    return "StoreInfo{$mungoType, $type}"
  }

  companion object {
    fun merge(a: StoreInfo, b: StoreInfo): StoreInfo {
      return StoreInfo(
        a.analyzer,
        a.mungoType.leastUpperBound(b.mungoType),
        a.analyzer.utils.leastUpperBound(a.type, b.type)
      )
    }

    fun intersect(a: StoreInfo, b: StoreInfo): StoreInfo {
      val types = a.analyzer.utils.typeUtils
      val mostSpecific = if (types.isAssignable(a.type, b.type)) {
        a.type
      } else if (types.isAssignable(b.type, a.type)) {
        b.type
      } else if (TypesUtils.isErasedSubtype(a.type, b.type, types)) {
        a.type
      } else if (TypesUtils.isErasedSubtype(b.type, a.type, types)) {
        b.type
      } else {
        a.type
      }
      return StoreInfo(a.analyzer, a.mungoType.intersect(b.mungoType), mostSpecific)
    }
  }
}

sealed class Store(private val map: Map<Reference, StoreInfo>) {

  operator fun contains(ref: Reference) = map.contains(ref)
  operator fun get(ref: Reference): StoreInfo? = map[ref]
  operator fun iterator(): Iterator<Map.Entry<Reference, StoreInfo>> = map.iterator()

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    return other is Store && this.map == other.map
  }

  override fun hashCode(): Int {
    return map.hashCode()
  }

  override fun toString(): String {
    return map.toString()
  }

  fun toMutable(): MutableStore {
    return MutableStore(map.toMutableMap())
  }

  open fun toImmutable(): Store {
    return this
  }

  companion object {
    val empty = MutableStore().toImmutable()

    fun merge(a: Store, b: Store): Store {
      if (a === b) return a
      return a.toMutable().merge(b).toImmutable()
    }

    fun mutableMergeFields(a: Store, b: Store): MutableStore {
      val newStore = MutableStore()
      for ((key, info) in a) {
        if (key.isThisField()) {
          newStore[key] = info
        }
      }
      for ((key, info) in b) {
        if (key.isThisField()) {
          newStore.merge(key, info)
        }
      }
      return newStore
    }
  }
}

class MutableStore(private val map: MutableMap<Reference, StoreInfo> = mutableMapOf()) : Store(map) {

  private var mutable = true

  private fun canMutate() {
    if (!mutable) {
      throw RuntimeException("Cannot mutate immutable store")
    }
  }

  operator fun set(ref: Reference, info: StoreInfo) {
    canMutate()
    map[ref] = info
  }

  // pre: canMutate()
  private fun _merge(ref: Reference, info: StoreInfo) {
    map.compute(ref) { _, curr -> if (curr == null) info else StoreInfo.merge(curr, info) }
  }

  fun merge(ref: Reference, info: StoreInfo) {
    canMutate()
    _merge(ref, info)
  }

  fun merge(other: Store): MutableStore {
    canMutate()
    for ((ref, info) in other) {
      _merge(ref, info)
    }
    return this
  }

  fun mergeFields(other: Store): MutableStore {
    for ((ref, info) in other) {
      if (ref.isThisField()) {
        _merge(ref, info)
      }
    }
    return this
  }

  fun intersect(ref: Reference, info: StoreInfo) {
    canMutate()
    map.compute(ref) { _, curr -> if (curr == null) info else StoreInfo.intersect(curr, info) }
  }

  fun remove(ref: Reference): StoreInfo? {
    canMutate()
    return map.remove(ref)
  }

  fun toBottom(): MutableStore {
    canMutate()
    for ((key, value) in map) {
      map[key] = StoreInfo(value, MungoBottomType.SINGLETON)
    }
    return this
  }

  fun invalidate(utils: MungoUtils): MutableStore {
    canMutate()
    for ((key, value) in map) {
      map[key] = StoreInfo(value, MungoTypecheck.invalidate(utils, key.type))
    }
    return this
  }

  fun invalidateFields(utils: MungoUtils): MutableStore {
    canMutate()
    for ((key, value) in map) {
      if (key.isThisField()) {
        map[key] = StoreInfo(value, MungoTypecheck.invalidate(utils, key.type))
      }
    }
    return this
  }

  fun invalidatePublicFields(utils: MungoUtils): MutableStore {
    canMutate()
    for ((key, value) in map) {
      if (key.isThisField() && key is FieldAccess && key.isNonPrivate) {
        map[key] = StoreInfo(value, MungoTypecheck.invalidate(utils, key.type))
      }
    }
    return this
  }

  override fun toImmutable(): Store {
    mutable = false
    return this
  }

}

sealed class EqualityTracker {

  // If two references are associated with the same integer,
  // that means they are known to point to the same value.

  protected val refToNum = mutableMapOf<Reference, Int>()
  protected val numToRefs = mutableMapOf<Int, MutableSet<Reference>>()

  operator fun get(ref: Reference): Set<Reference> {
    val num = refToNum[ref] ?: return setOf(ref)
    return numToRefs[num]!!
  }

}

class MutableEqualityTracker : EqualityTracker() {

  private var nextNum = 0

  fun setEquality(a: Reference, b: Reference) {
    if (a == b) {
      return
    }
    val aNum = refToNum[a]
    val bNum = refToNum[b]

    when {
      aNum == null && bNum == null -> {
        val newNum = nextNum++
        refToNum[a] = newNum
        refToNum[b] = newNum
        numToRefs[newNum] = mutableSetOf(a, b)
      }
      aNum == null -> {
        refToNum[a] = bNum!!
        numToRefs[bNum]!!.add(a)
      }
      bNum == null -> {
        refToNum[b] = aNum
        numToRefs[aNum]!!.add(b)
      }
      aNum < bNum -> {
        val aSet = numToRefs[aNum]!!
        val bSet = numToRefs.remove(bNum)!!
        for (r in bSet) {
          refToNum[r] = aNum
          aSet.add(r)
        }
      }
      aNum > bNum -> {
        val aSet = numToRefs.remove(aNum)!!
        val bSet = numToRefs[bNum]!!
        for (r in aSet) {
          refToNum[r] = bNum
          bSet.add(r)
        }
      }
    }
  }

  fun invalidate(ref: Reference) {
    val num = refToNum.remove(ref) ?: return
    numToRefs[num]!!.remove(ref)
  }

}
