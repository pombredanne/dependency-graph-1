package dependency_graph

import java.util.{Map => JMap}
import Cache.Entry
import org.joda.time.DateTime
import scala.collection.convert.decorateAsScala._

object Cache {

  private final class Entry[A](val value: A, expiresAt: DateTime){
    def expired(): Boolean = expiresAt.isBefore(DateTime.now)
    def notExpired(): Boolean = ! expired
  }

  def create[A, B](maxSize: Int): Cache[A, B] =
    new Cache[A, B](
      java.util.Collections.synchronizedMap(new java.util.LinkedHashMap[A, Entry[B]](maxSize + 1, 1.0f, false){
        override def removeEldestEntry(eldest: JMap.Entry[A, Entry[B]]) = {
          size() > maxSize || eldest.getValue.expired
        }
      })
    )
}

final class Cache[A, B] private(underlying: JMap[A, Entry[B]]) {

  def keys: Set[A] = underlying.keySet().asScala.toSet

  def get(key: A): Option[B] = {
    underlying.get(key) match {
      case null => None
      case entry =>
        if (entry.expired()) {
          underlying.remove(key)
          None
        } else {
          Some(entry.value)
        }
    }
  }

  def put(key: A, value: B, expire: DateTime): Boolean = {
    if (expire.isAfter(DateTime.now())) {
      val entry = new Entry(value, expire)
      underlying.put(key, entry)
      true
    } else {
      false
    }
  }

  def getOrElseUpdate(key: A, orElse: => B, expire: DateTime): B = {
    get(key).getOrElse {
      val value = orElse
      put(key, value, expire)
      value
    }
  }

  override def toString = underlying.keySet().toString
}
