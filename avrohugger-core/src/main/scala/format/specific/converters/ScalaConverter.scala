package avrohugger
package format
package specific
package converters

import matchers.TypeMatcher
import stores.ClassStore
import types._

import treehugger.forest._
import definitions._
import treehuggerDSL._

import org.apache.avro.Schema

import scala.language.postfixOps
import scala.collection.JavaConverters._


object ScalaConverter {

  def checkCustomArrayType(
    arrayType: AvroScalaArrayType,
    elementType: Type,
    seqArgs: Typed,
    defaultConversion: Tree) = {
    val classTagIdent = REF(s"scala.reflect.ClassTag(classOf[$elementType])")
    val arrayConversion = ARRAY(seqArgs).APPLY(classTagIdent).AS(TYPE_ARRAY(elementType))
    arrayType match {
      case ScalaArray  => arrayConversion
      case ScalaList   => LIST(seqArgs)
      case ScalaSeq    => SEQ(seqArgs)
      case ScalaVector => VECTOR(seqArgs)
    }
  }
  
  // takes as args a REF wrapped according to field Type
  def convertFromJava(
    classStore: ClassStore,
    namespace: Option[String],
    schema: Schema,
    tree: Tree, 
    typeMatcher: TypeMatcher): Tree = {

    schema.getType match {
      case Schema.Type.ARRAY => {
        val elementSchema = schema.getElementType
        val elementType = typeMatcher.toScalaType(classStore, namespace, elementSchema)
        val JavaList = RootClass.newClass("java.util.List[_]")
        val applyParam = REF("array") DOT("iterator")
        val elementConversion = convertFromJava(classStore, namespace, elementSchema, REF("x"), typeMatcher)
        val seqArgs = {
          SEQARG(
            REF("scala.collection.JavaConverters.asScalaIteratorConverter").APPLY(applyParam).DOT("asScala").DOT("toSeq")
              .MAP(LAMBDA(PARAM("x")) ==> BLOCK(elementConversion))
          )
        }
        val arrayType = typeMatcher.avroScalaTypes.array
        val resultExpr = BLOCK(
          checkCustomArrayType(arrayType, elementType, seqArgs, LIST(seqArgs))
        )
        val arrayConversion = CASE(ID("array") withType(JavaList)) ==> resultExpr
        val errorMessage = INTERP("s", LIT(s"expected array with type $JavaList, found "), LIT("array"))
        val errorExpr = NEW("org.apache.avro.AvroRuntimeException", errorMessage)
        val conversionCases = List(arrayConversion)
        val arrayMatchError = CASE(WILDCARD) ==> errorExpr
        tree MATCH(conversionCases:_*)
      }
      case Schema.Type.STRING => tree TOSTRING
      case Schema.Type.MAP => {
        val JavaMap = RootClass.newClass("java.util.Map[_,_]")
        val resultExpr = {
          BLOCK(
            REF("scala.collection.JavaConverters.mapAsScalaMapConverter")
            .APPLY(REF("map"))
            .DOT("asScala")
            .DOT("toMap")
            .MAP(LAMBDA(PARAM("kvp")) ==> BLOCK(
              VAL("key") := REF("kvp._1").DOT("toString"),
              VAL("value") := REF("kvp._2"),
              PAREN(REF("key"), convertFromJava(classStore, namespace, schema.getValueType, REF("value"), typeMatcher)))
            )
          )
        }
        val mapConversion = CASE(ID("map") withType(JavaMap)) ==> resultExpr
        tree MATCH(mapConversion)
      }
      case Schema.Type.FIXED    => sys.error("the FIXED datatype is not yet supported")
      case Schema.Type.BYTES => {
        val JavaBuffer = RootClass.newClass("java.nio.ByteBuffer")
        val resultExpr = Block(
          REF("buffer") DOT "array" APPLY()
        )
        val bufferConversion = CASE(ID("buffer") withType(JavaBuffer)) ==> resultExpr
        tree MATCH bufferConversion
      }
      case Schema.Type.UNION  => {
        val types = schema.getTypes.asScala
        // check if it's the kind of union that we support (i.e. nullable fields)
        if (types.length != 2 ||
           !types.map(x => x.getType).contains(Schema.Type.NULL) ||
            types.filterNot(x => x.getType == Schema.Type.NULL).length != 1) {
              sys.error("Unions beyond nullable fields are not supported")
        }
        // the union represents a nullable field, the kind of union supported in avrohugger
        else {
          val typeParamSchema = types.find(x => x.getType != Schema.Type.NULL).get
          val nullConversion = CASE(NULL) ==> NONE
          val someExpr = SOME(convertFromJava(classStore, namespace, typeParamSchema, tree, typeMatcher))
          val someConversion = CASE(WILDCARD) ==> someExpr
          val conversionCases = List(nullConversion, someConversion)
          tree MATCH(conversionCases:_*)
        }
      }
      case Schema.Type.ENUM => {
        typeMatcher.avroScalaTypes.enum match {
          case EnumAsScalaString => tree TOSTRING
          case JavaEnum | ScalaEnumeration | ScalaCaseObjectEnum => tree
        }
      }
      case _ => tree
    }
  }

}
