package org.positronicnet.orm

import org.positronicnet.content._
import org.positronicnet.util._

import android.database.Cursor

import scala.collection._

// Trait for objects that will be persisted by this ORM into some
// ContentRepository (be it a SQLiteDatabase, a ContentProvider, or whatever).
//
// Most of the heavy lifting is delegated to a RecordManager singleton,
// for which see below.

trait ManagedRecord {

  // Manager of this record type.  MUST be a "RecordManager[ ThisClass ]",
  // but it's really awkward to declare it that way.

  private [orm] 
  def manager: RecordManager[ _ ]

  // Bookkeeping

  private [orm]
  var newRecord = true

  private [orm]
  var unsaved = true

  def isNewRecord = newRecord
  def isUnsaved   = unsaved

  // Actions on individual records, delegated to the manager...

  def delete = manager.delete( this )
  def save = manager.save( this )
}

// Class that actually manages shuffling in-core records into and
// out of persistent storage.

abstract class RecordManager[ T <: ManagedRecord : ClassManifest ]( facility: AppFacility )
  extends ChangeManager( facility )
  with BaseScope[T]
{
  // Interface to storage...
  // Where we store stuff.

  def repository: ContentQuery[_,_]

  // Producing a new object (to be populated with mapped data from a query). 
  // Note that the default implementation requires a niladic constructor 
  // to exist in bytecode, which will *not* be the case if there's a
  // with-args constructor that supplies defaults for all args (viz.
  // case classes).  For now, RecordManagers can override; for later,
  // the reflection to deal with this situation is possible, but painful.

  def newRecord = klass.newInstance.asInstanceOf[T]  // if niladic constructor exists!

  // Setting up the mapping of fields to storage columns.

  private val klass = classManifest[T].erasure
  private val javaFields = ReflectUtils.declaredFieldsByName( klass )

  private val fields = new mutable.ArrayBuffer[ MappedField ]
  private val nonKeyFields = new mutable.ArrayBuffer[ MappedField ]
  private var primaryKeyField: MappedLongField = null

  def mapField( fieldName: String, 
                columnName: String, 
                primaryKey: Boolean = false ): Unit = 
  {
    val javaField = javaFields( fieldName )

    if (javaField == null) {
      throw new IllegalArgumentException( "Can't map nonexistent field " 
                                          + fieldName )
    }

    val idx = fields.size
    val mappedField = MappedField.create( columnName, idx, javaField )

    fields += mappedField
    if (!primaryKey) {
      nonKeyFields += mappedField
    }
    else {
      if (primaryKeyField != null) {
        throw new IllegalArgumentException( "Multiple primary key fields" )
      }
      mappedField match {
        case f: MappedLongField => 
          primaryKeyField = f
        case _ => 
          throw new IllegalArgumentException("Primary key field must be Long")
      }
    }
  }

  // Feeding the Scope machinery what it needs

  private [orm] val mgr = this
  private [orm] val baseQuery = repository

  // Dealing with the data... internals

  private lazy val fieldNames = fields.map{ _.name }

  private [orm]
  def rawQuery( qry: ContentQuery[_,_] ): Seq[ T ] = {
    qry.select( fieldNames: _* ).map{ c => instantiateFrom( c ) }
  }

  private [orm] 
  def instantiateFrom( c: Cursor ): T = {
    val result = newRecord
    result.newRecord = false            // we just retrieved it...
    result.unsaved = false              // ... and it's not yet altered.
    for( field <- fields ) field.setFromCursorColumn( result, c )
    return result
  }

  private [orm]
  def save( rec: ManagedRecord ) = {
    val data = nonKeyFields.map{ f => f.valPair( rec ) }

    doChange {
      if (rec.newRecord) 
        repository.insert( data: _* )
      else
        repository.whereEq( primaryKeyField.valPair( rec )).update( data: _* )
    }
  }

  private [orm]
  def delete( rec: ManagedRecord ): Unit = 
    whereEq( primaryKeyField.valPair( rec )).deleteAll
}

object ReflectUtils
{
  // Technique borrowed from sbt's ReflectUtilities, cut down to fit here.

  def ancestry( klass: Class[_] ): List[ Class[_]] =
    if (klass == classOf[ AnyRef ]) List( klass )
    else klass :: ancestry( klass.getSuperclass )

  def declaredFieldsByName( klass: Class[_] ) = {
    val fieldList = ancestry( klass ).flatMap( _.getDeclaredFields )
    Map( fieldList.map( f => (f.getName, f )): _* )
  }
}