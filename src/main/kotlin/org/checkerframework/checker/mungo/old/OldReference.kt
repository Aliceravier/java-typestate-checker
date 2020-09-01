package org.checkerframework.checker.mungo.old

sealed class OldReference {
  abstract fun isThisField(): Boolean
}

class Id(val name: String) : OldReference() {
  override fun toString(): String {
    return name
  }

  override fun isThisField(): Boolean {
    return name == "this"
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Id) return false
    return name == other.name
  }

  override fun hashCode(): Int {
    return name.hashCode()
  }
}

class Member(val ref: OldReference, val name: String) : OldReference() {
  override fun toString(): String {
    return "$ref.$name"
  }

  override fun isThisField(): Boolean {
    return ref.isThisField()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Member) return false
    return name == other.name && ref == other.ref
  }

  override fun hashCode(): Int {
    return 31 * ref.hashCode() + name.hashCode()
  }
}
