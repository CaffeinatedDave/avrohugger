package avrohugger
package format
package standard

import avrohugger.format.abstractions.Importer
import avrohugger.matchers.TypeMatcher
import avrohugger.stores.SchemaStore
import avrohugger.types._
import org.apache.avro.Schema.Type.RECORD
import org.apache.avro.{Protocol, Schema}
import org.codehaus.jackson.JsonNode
import treehugger.forest._
import definitions._
import treehuggerDSL._

import scala.collection.JavaConverters._

object StandardImporter extends Importer {

  /**
    * Shapeless representations of Coproduct are by default only active when `union`
    * is defined with more than two types or three types where one of them is nullable.
    * Otherwise the default values require no special imports
    * since they are codegen in terms of [[Option]] and [[Either]]
    */
  private[this] def containsShapelessFields(schema: Schema, typeMatcher: TypeMatcher): Boolean =
    typeMatcher.avroScalaTypes.union match {
      case OptionShapelessCoproduct => true
      case OptionEitherShapelessCoproduct => {
        val fields = schema.getFields.asScala
        val isUnion: Schema.Field => Boolean = _.schema().getType == Schema.Type.UNION
        val unionContainsNull: Schema => Boolean = _.getType == Schema.Type.NULL
        val requiresImports =
          fields
            .filter(isUnion)
            .exists(uf => {
              val unionTypes = uf.schema().getTypes.asScala
              (unionTypes.length > 2 && !unionTypes.exists(unionContainsNull)) ||
              (unionTypes.length > 3 && unionTypes.exists(unionContainsNull))
            })
        requiresImports
      }
    }
    
  private[this] def containsShapelessDefaultValues(schema: Schema, typeMatcher: TypeMatcher): Boolean =
    typeMatcher.avroScalaTypes.union match {
      case OptionShapelessCoproduct => true
      case OptionEitherShapelessCoproduct => {
        val fields = schema.getFields.asScala
        val isUnion: Schema.Field => Boolean = _.schema().getType == Schema.Type.UNION
        val unionContainsNull: Schema => Boolean = _.getType == Schema.Type.NULL
        val requiresImports =
          fields
            .filter(isUnion)
            .exists(uf => {
              val unionTypes = uf.schema().getTypes.asScala
              (unionTypes.length > 2 && !unionTypes.exists(unionContainsNull) && uf.defaultValue != null) ||
              (unionTypes.length > 3 &&  unionTypes.exists(unionContainsNull) && uf.defaultValue != null)
            })
        requiresImports
      }
    }

  def getImports(
    schemaOrProtocol: Either[Schema, Protocol],
    currentNamespace: Option[String],
    schemaStore: SchemaStore,
    typeMatcher: TypeMatcher): List[Import] = {
    def shapelessImport(shapelessCopSymbols: List[String]): Import = {
      val shapelessCopSymbolsImport = RootClass.newClass(s"shapeless.{${shapelessCopSymbols.mkString(", ")}}")
      IMPORT(shapelessCopSymbolsImport)
    }
    val topLevelSchemas = getTopLevelSchemas(schemaOrProtocol, schemaStore, typeMatcher)
    val recordSchemas = getRecordSchemas(topLevelSchemas)
    val deps = getRecordImports(recordSchemas, currentNamespace, typeMatcher)


    schemaOrProtocol match {
      case Left(schema) => {
        if (schema.getType == RECORD && containsShapelessDefaultValues(schema, typeMatcher))
          shapelessImport(List(":+:", "CNil", "Coproduct")) :: deps
        else if (schema.getType == RECORD && containsShapelessFields(schema, typeMatcher))
          shapelessImport(List(":+:", "CNil")) :: deps
        else
          deps
      }
      case Right(protocol) => {
        val types = protocol.getTypes.asScala.toList
        if (types.exists(s => s.getType == RECORD && containsShapelessDefaultValues(s, typeMatcher)))
          shapelessImport(List(":+:", "CNil", "Coproduct")) :: deps
        else if (types.exists(s => s.getType == RECORD && containsShapelessFields(s, typeMatcher)))
          shapelessImport(List(":+:", "CNil")) :: deps
        else
          deps
      }
    }
  }

}