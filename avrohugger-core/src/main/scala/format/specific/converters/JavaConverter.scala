package avrohugger
package format
package specific
package converters

import treehugger.forest._
import definitions._
import treehuggerDSL._

import org.apache.avro.Schema

import scala.collection.JavaConverters._


object JavaConverter {
  // Recursive definition takes a field's schema, and a tree that represents the source code to be written.
  // The initial tree that is passed in is a REF("fieldName"), which is wrapped in a pattern match tree (e.g.,
  // to sort None and Some(x) if the field is a union). A Schema is passed in order to get access to the field's type
  // parameters while the tree is built up.
  def convertToJava(schema: Schema, tree: Tree): Tree  = {
    schema.getType match {
      case Schema.Type.UNION => {
        val types = schema.getTypes.asScala
        // check if it's the kind of union that we support (i.e. nullable fields)
        if (types.length != 2 ||
           !types.map(x => x.getType).contains(Schema.Type.NULL) ||
            types.filterNot(x => x.getType == Schema.Type.NULL).length != 1) {
          sys.error("Unions beyond nullable fields are not supported")
        }
        else {
          val maybeType = types.find(x => x.getType != Schema.Type.NULL)
          if (maybeType.isDefined) {
          val conversionCases = List(
            CASE(SOME(ID("x"))) ==> convertToJava(maybeType.get, REF("x")),
            CASE(NONE)          ==> NULL
          )
          tree MATCH(conversionCases:_*)
          }
          else sys.error("There was no type in this union")
        }
      }
      case Schema.Type.ARRAY => {
        val applyParam = {
          BLOCK(tree MAP(LAMBDA(PARAM("x")) ==> BLOCK(
            convertToJava(schema.getElementType, REF("x"))
          )))
        }
        REF("scala.collection.JavaConverters.bufferAsJavaListConverter").APPLY(applyParam DOT "toBuffer").DOT("asJava")
      }
      case Schema.Type.MAP      => {
        val HashMapClass = RootClass.newClass("java.util.HashMap[String, Any]")
        BLOCK(
          VAL("map", HashMapClass) := NEW(HashMapClass),
          tree FOREACH( LAMBDA(PARAM("kvp")) ==>
            BLOCK(
              VAL("key") := REF("kvp._1"),
              VAL("value") := REF("kvp._2"),
              REF("map").DOT("put").APPLY(REF("key"), convertToJava(schema.getValueType, REF("value")))
            )
          ),
          REF("map")
        )
      }
      case Schema.Type.FIXED => sys.error("the FIXED datatype is not yet supported")
      case Schema.Type.BYTES =>
        if (schema.getLogicalType == null)
          REF("java.nio.ByteBuffer") DOT "wrap" APPLY tree
        else tree
      case _ => tree
    }
  }

}
