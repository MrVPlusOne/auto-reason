package lambdanet.utils

import ammonite.ops._
import funcdiff.SimpleMath
import lambdanet._
import lambdanet.utils.Js._
import lambdanet.Surface._
import lambdanet.translation.OldIRTranslation
import SimpleMath.Extensions._
import fastparse.Parsed
import lambdanet.translation.ImportsResolution.PathMapping
import lambdanet.translation.groupInBlockSurface

import scala.collection.mutable

/** Parsing Typescript into the surface language */
object ProgramParsing {
  type GTMark = TyAnnot

  case class DeclarationModule(
      varDefs: Map[Symbol, GType],
      typeDefs: Map[Symbol, GType],
      namespaces: Map[Symbol, DeclarationModule]
  )

  @deprecated
  def extractDeclarationModule(stmts: Vector[GStmt]): DeclarationModule = {
    val varDefs: mutable.Map[Symbol, GType] = mutable.HashMap()
    val typeDefs: mutable.Map[Symbol, GType] = mutable.HashMap()
    val namespaces: mutable.Map[Symbol, DeclarationModule] = mutable.HashMap()

    stmts.foreach {
      case VarDef(
          x,
          Annot.WithContent(t),
          _: Const,
          _,
          ExportLevel.Unspecified
          ) =>
        varDefs(x) = t
      case f: FuncDef =>
        varDefs(f.name) = GStmt.extractSignature(f)
      case alias @ TypeAliasStmt(name, tyVars, ty, ExportLevel.Unspecified) =>
        val rhs = try {
          OldIRTranslation.translateType(ty)(tyVars.toSet)
        } catch {
          case _: ClassCastException => throw new Error(s"Failed for $alias")
        }

        typeDefs(name) = typeDefs.get(name) match {
          case Some(o1: ObjectType) =>
            o1.merge(rhs.asInstanceOf[ObjectType])
          case Some(_) => throw new Error("Contradicting type aliases")
          case None    => rhs
        }
      case _: CommentStmt =>
      case ns: Namespace =>
        namespaces(ns.name) = extractDeclarationModule(ns.block.stmts)
      case other =>
        throw new Error(
          s"Illegal statement encountered in a declaration file: $other"
        )
    }

    DeclarationModule(varDefs.toMap, typeDefs.toMap, namespaces.toMap)
  }

  case class PackageFile(
      moduleName: Option[ProjectPath],
      devDependencies: Set[ProjectPath]
  )
  def parsePackageFile(path: Path): PackageFile = {
    val map = asObj(parseJsonFromFile(path))
    PackageFile(
      moduleName = map.get("name").map(s => RelPath(asString(s))),
      devDependencies = map
        .get("devDependencies")
        .map { m =>
          asObj(m).keySet.map(RelPath(_))
        }
        .getOrElse(Set())
    )
  }

  case class TsConfigFile(
      baseUrl: Option[RelPath]
  )

  def parseTsConfigFile(path: Path): TsConfigFile = {
    val map = asObj(parseJsonFromFile(path))
    val baseOpt = for {
      opt <- map.get("compilerOptions")
      url <- asObj(opt).get("baseUrl")
    } yield RelPath(asString(url))
    TsConfigFile(baseOpt)
  }

  case class GProject(
      root: Path,
      modules: Vector[GModule],
      pathMapping: PathMapping,
      subProjects: Map[ProjectPath, ProjectPath],
      devDependencies: Set[ProjectPath]
  )

  def parseGProjectFromRoot(
      root: Path,
      declarationFileMod: Boolean = false,
      filter: Path => Boolean = _ => true
  ): GProject = {
    val subProjects =
      (for {
        f <- ls.rec(root) if f.last == "package.json"
        name <- parsePackageFile(f).moduleName
      } yield {
        def isIndexFile(p: FilePath): Boolean = {
          p.last == "index.ts" || p.last == "index.d.ts"
        }

        val linkTarget =
          if (ls(f / up).any(isIndexFile)) f / up
          else f / up / "src"
        name -> linkTarget.relativeTo(root)
      }).toMap

    type Dir = ProjectPath
    val baseDirs = {
      def rec(
          path: Path,
          baseUrl: Option[ProjectPath]
      ): Map[Dir, Option[ProjectPath]] = {
        require(path.isDir)
        val paths = ls(path)
        val newBase = lambdanet.combineOption(
          baseUrl,
          paths
            .find(_.last == "tsconfig.json")
            .flatMap(
              f =>
                parseTsConfigFile(f).baseUrl
                  .map((f / up).relativeTo(root) / _)
            )
        )

        Map(path.relativeTo(root) -> newBase) ++ paths
          .filter(_.isDir)
          .flatMap(rec(_, newBase))
      }
      rec(root, None)
    }

    val mapping = new PathMapping {
      def map(currentPath: ProjectPath, path: ProjectPath): ProjectPath = {
        subProjects.get(path).foreach { s =>
          return s
        }
        val base = baseDirs(currentPath).getOrElse(currentPath)
        base / path
      }

      val aliases: Map[ProjectPath, ProjectPath] =
        (for {
          f <- ls.rec(root) if f.isSymLink
          pointsTo = f.tryFollowLinks.get
        } yield f.relativeTo(root) -> pointsTo.relativeTo(root)).toMap
    }

    val sources = ls
      .rec(root)
      .filter(filter)
      .filter { f =>
        val name = f.last
        if (declarationFileMod) name.endsWith(".d.ts")
        else
          name.endsWith(".ts") || name.endsWith(".d.ts") || name.endsWith(
            ".tsx"
          )
      }
      .map(_.relativeTo(root))

    def handleTypesPrefix(p: ProjectPath): Set[ProjectPath] = {
      if(p.segments.head == "@types") Set(RelPath(p.segments.tail, 0), p)
      else Set(p)
    }

    val devDependencies = (for {
      f <- ls.rec(root) if f.last == "package.json"
    } yield parsePackageFile(f))
      .toSet[PackageFile]
      .flatMap(_.devDependencies.flatMap(handleTypesPrefix))

    GProject(
      root,
      ProgramParsing.parseGModulesFromFiles(sources, root),
      mapping,
      subProjects,
      devDependencies
    )
  }

  def parseContent(content: String): Vector[GStmt] = {
    SimpleMath.withErrorMessage(
      "failed when parsing content: \n" + content
    ) {
      val r = %%('node, "./parsingFromString.js", content)(pwd / 'scripts / 'ts)
      val json = ProgramParsing.parseJson(r.out.string).asInstanceOf[Js.Arr]
      json.value.toVector.flatMap {
        parseGStmt
      }
    }
  }

  def parseJson(text: String): Js.Val = {
    SimpleMath.withErrorMessage(s"JSON source text: $text") {
      JsonParsing.parseJson(text)
    }
  }

  def parseJsonFromFile(jsonFile: Path): Js.Val =
    SimpleMath.withErrorMessage(s"Failed to parse json from file: $jsonFile") {
      val text = read(jsonFile)
      if(text.trim.isEmpty){
        throw new Error(s"Trying to parse Json from an empty file: '$jsonFile'")
      }
      fastparse.parse(text, JsonParsing.jsonExpr(_)) match {
        case Parsed.Success(value, _) => value
        case Parsed.Failure(_, _, extra) =>
          throw new Error("Parsing error: " + extra.trace().aggregateMsg)
      }
    }

  /**
    * only srcFiles get parsed into [[GModule]]s, libraryFiles are simply provided to
    * the compiler for parsing purposes. Both kinds of paths are relative to the project
    * root.
    */
  def parseGModulesFromFiles(
      srcFiles: Seq[RelPath],
      projectRoot: Path
  ): Vector[GModule] = {
    val r = %%(
      'node,
      pwd / RelPath("scripts/ts/parsingFromFile.js"),
      "--src",
      srcFiles.map(_.toString()),
      "--lib",
      srcFiles.toList.map(_.toString())
    )(projectRoot)
    // fixme: this never fires
    if (r.exitCode != 0) {
      throw new Error(s"TS compiler parsing failed: ${r.out.string}")
    }
    val parsedJson = r.out.string
    parseGModulesFromJson(parsedJson)
  }

  /** Parses a sequence of [[GModule]] from Json string. These strings can be
    * generated through [[parseGModulesFromFiles]] when writeToFile is set to none-empty. */
  def parseGModulesFromJson(parsedJson: String): Vector[GModule] = {
    val modules = ProgramParsing.parseJson(parsedJson).asInstanceOf[Js.Arr]
    modules.value
      .map(parseGModule)
      .groupBy(m => (m.isDeclarationFile, m.path))
      .map {
        case ((isIndex, path), ms) =>
          GModule(path, ms.toVector.flatMap(_.stmts), isIndex)
      }
      .toVector
  }

  def asString(v: Js.Val): String = v.asInstanceOf[Str].value

  def asArray(v: Js.Val): List[Val] = v match {
    case Js.Null => List[Val]()
    case _       => v.asInstanceOf[Arr].value.toList
  }

  def asVector(v: Js.Val): Vector[Val] = v match {
    case Js.Null => Vector[Val]()
    case _       => v.asInstanceOf[Arr].value.toVector
  }

  def asNumber(v: Js.Val): Double = v.asInstanceOf[Num].value

  def asSymbol(v: Js.Val): Symbol = Symbol(asString(v))

  def asOptionSymbol(v: Js.Val): Option[Symbol] = v match {
    case Null => None
    case _    => Some(asSymbol(v))
  }

  def asObj(v: Val): Map[String, Val] = v.asInstanceOf[Obj].value

  def parseNamedValue(v: Js.Val): (String, Val) = {
    val p = asObj(v)
    asString(p("name")) -> p("value")
  }

  def arrayToMap(value: Js.Val): Map[String, Val] = {
    value
      .asInstanceOf[Arr]
      .value
      .map {
        parseNamedValue
      }
      .toMap
  }

  def asBoolean(v: Js.Val): Boolean = {
    (v: @unchecked) match {
      case False => false
      case True  => true
    }
  }

  private def parseType(v: Js.Val): GType = {
    assert(
      v != Null,
      "Use parseGTMark instead if you are parsing an optional user type annotation."
    )
    val o = asObj(v)
    val t = asString(o("category")) match {
      case "TVar" =>
        val n = asSymbol(o("name"))
        if (n == AnyType.id) AnyType else TyVar(n)
      case "AnyType" => AnyType
      case "FuncType" =>
        val fr = asArray(o("args")).map(parseType)
        val to = parseType(o("to"))
        FuncType(fr, to)
      case "ObjectType" =>
        val fields = asArray(o("fields"))
          .map(pair => {
            val (k, v) = parseNamedValue(pair)
            (Symbol(k), parseType(v))
          })
          .toMap
        ObjectType(fields)
    }
    t
  }

  private def parseArgPair(value: Js.Val): (Symbol, GTMark) = {
    val (name, v) = parseNamedValue(value)
    val ty = parseGTMark(v)
    (Symbol(name), ty)
  }

  private def parseArgList(value: Js.Val): Vector[(Symbol, GTMark)] = {
    val list = asVector(value)
    list.map(parseArgPair)
  }

  private def parseGTMark(v: Js.Val): GTMark = {
    v match {
      case Null => Annot.Missing
      case _    => Annot.User(parseType(v))
    }
  }

  private def parseGExpr(v: Js.Val): GExpr = {
    val map = asObj(v)
    asString(map("category")) match {
      case "FuncCall" =>
        val f = parseGExpr(map("f"))
        val args = asArray(map("args")).map(parseGExpr)
        FuncCall(f, args)

      case "Var" =>
        val name = asSymbol(map("name"))
        Var(name)
      case "Const" =>
        val ty = parseType(map("ty")).asInstanceOf[GroundType]
        val value = asString(map("value"))
        val c = Const(value, ty)
        c.line = asNumber(map("line")).toInt
        c
      case "ObjLiteral" =>
        val obj = arrayToMap(map("fields"))
        val objMap = obj.map { case (x, y) => (Symbol(x), parseGExpr(y)) }
        ObjLiteral(objMap)
      case "Access" =>
        val expr = parseGExpr(map("expr"))
        val field = asSymbol(map("field"))
        Access(expr, field)
      case "IfExpr" =>
        val cond = parseGExpr(map("cond"))
        val e1 = parseGExpr(map("e1"))
        val e2 = parseGExpr(map("e2"))
        //        val resultType = parseType(map("resultType"))
        IfExpr(cond, e1, e2)

      case cat => throw new Error(s"Unhandled GExpr case: $cat")
    }
  }

  private case class DefModifiers(
      isConst: Boolean,
      exportLevel: ExportLevel.Value,
      isGetter: Boolean,
      isSetter: Boolean,
      isAbstract: Boolean
  )

  private def parseModifiers(v: Js.Val): DefModifiers = {
    val modifiers = asArray(v).map(asString).toSet
    val isConst = modifiers.contains("const")
    val exportLevel =
      if (modifiers.contains("export"))
        if (modifiers.contains("default")) ExportLevel.Default
        else ExportLevel.Public
      else ExportLevel.Unspecified
    val isGetter = modifiers.contains("get")
    val isSetter = modifiers.contains("set")
    val isAbstract = modifiers.contains("abstract")
    DefModifiers(isConst, exportLevel, isGetter, isSetter, isAbstract)
  }

  private def parseGStmt(v: Js.Val): Vector[GStmt] =
    SimpleMath.withErrorMessage(s"Error when parsing $v") {
      val map = asObj(v)
      asString(map("category")) match {
        case "VarDef" =>
          val name = asString(map("x"))
          val t = parseGTMark(map("mark"))
          val init = parseGExpr(map("init"))
          val ms = parseModifiers(map("modifiers"))
          val b = asBoolean(map("isConst")) || ms.isConst
          Vector(VarDef(Symbol(name), t, init, isConst = b, ms.exportLevel))
        case "AssignStmt" =>
          val lhs = parseGExpr(map("lhs"))
          val rhs = parseGExpr(map("rhs"))
          Vector(AssignStmt(lhs, rhs))
        case "ExprStmt" =>
          val e = parseGExpr(map("expr"))
          val isReturn = asBoolean(map("isReturn"))
          Vector(ExprStmt(e, isReturn))
        case "IfStmt" =>
          val cond = parseGExpr(map("cond"))
          val branch1 = parseGStmt(map("branch1"))
          val branch2 = parseGStmt(map("branch2"))
          Vector(
            IfStmt(
              cond,
              groupInBlockSurface(branch1),
              groupInBlockSurface(branch2)
            )
          )
        case "WhileStmt" =>
          val cond = parseGExpr(map("cond"))
          val body = parseGStmt(map("body"))
          Vector(WhileStmt(cond, groupInBlockSurface(body)))
        case "CommentStmt" =>
          val text = asString(map("text"))
          Vector(CommentStmt(text))
        case "BlockStmt" =>
          val stmts = asVector(map("stmts")).flatMap(parseGStmt)
          Vector(BlockStmt(stmts))
        case "NamespaceStmt" =>
          val name = asString(map("name"))
          val body = groupInBlockSurface(parseGStmt(map("block")))
          val ms = parseModifiers(map("modifiers"))
          Vector(Namespace(Symbol(name), body, ms.exportLevel))
        case "FuncDef" =>
          val name = Symbol(asString(map("name")))
          val args = parseArgList(map("args"))
          val returnType =
            if (name == 'Constructor) Annot.Missing
            else parseGTMark(map("returnType"))
          val body = groupInBlockSurface(parseGStmt(map("body")))

          val tyVars = asVector(map("tyVars")).map(asSymbol)
          val ms = parseModifiers(map("modifiers"))
          Vector(FuncDef(name, tyVars, args, returnType, body, ms.exportLevel))
        case "TypeAliasStmt" =>
          val name = Symbol(asString(map("name")))
          val tyVars = asVector(map("tyVars")).map(asSymbol)
          val ty = parseType(map("type"))
          val ms = parseModifiers(map("modifiers"))
          Vector(TypeAliasStmt(name, tyVars, ty, ms.exportLevel))
        case "ClassDef" =>
          val name = asSymbol(map("name"))
          val superType = asOptionSymbol(map("superType"))
          val ms = parseModifiers(map("modifiers"))
          val tyVars = asVector(map("tyVars")).map(asSymbol)
          val vars = asVector(map("vars")).map { v1 =>
            val (name, v2) = parseNamedValue(v1)
            val List(tyV, initV, isStaticV) = asArray(v2)
            val ty = parseGTMark(tyV)
            val init = parseGExpr(initV)
            val isStatic = asBoolean(isStaticV)
            (Symbol(name), (ty, init, isStatic))
          }
          val funcDefs =
            asVector(map("funcDefs")).map { v =>
              val List(s, b) = asArray(v)
              parseGStmt(s).asInstanceOf[Vector[FuncDef]].head -> asBoolean(b)
            }

          val instanceInits = mutable.HashMap[Symbol, GExpr]()

          val (instanceVars, staticVars) = {
            val v1 = vars.groupBy(_._2._3)
            (
              v1.getOrElse(false, Map()).map {
                case (s, (mark, expr, _)) =>
                  if (expr != Var(undefinedSymbol))
                    instanceInits(s) = expr
                  s -> mark
              },
              v1.getOrElse(true, Map()).map(p => p._1 -> (p._2._1, p._2._2))
            )
          }
          val (instanceMethods, staticMethods0) = {
            val v1 = funcDefs.groupBy(_._2)
            (
              v1.getOrElse(false, Vector()).map(_._1),
              v1.getOrElse(true, Vector()).map(_._1)
            )
          }

          val constructor0 = {
            val constructorValue = map("constructor")
            val f = if (constructorValue == Null) {
              // make an empty constructor
              val tyVars = Vector()
              FuncDef(
                GStmt.constructorName,
                tyVars,
                Vector(),
                Annot.Missing,
                BlockStmt(Vector()),
                ExportLevel.Unspecified
              )
            } else {
              parseGStmt(constructorValue).asInstanceOf[Vector[FuncDef]].head
            }
            f.copy(
              name = GStmt.constructorName,
              returnType = Annot.Fixed(TyVar(name)),
              tyVars = tyVars // constructor has the same tyVars as the class
            )
          }
          //put instance var instantiation into the constructor
          val constructor = {
            val thisDef = VarDef(
              thisSymbol,
              Annot.Fixed(TyVar(name)),
              Var(undefinedSymbol),
              isConst = true,
              ExportLevel.Unspecified
            )
            val thisSuperVars = Vector(
              thisDef,
              thisDef.copy(name = superSymbol)
            )
            val lambdas = asVector(map("instanceLambdas"))
              .flatMap(parseGStmt)
              .asInstanceOf[Vector[FuncDef]]
            val stmts = groupInBlockSurface(Vector(constructor0.body)).stmts
            val inits = instanceInits.toVector.map {
              case (s, expr) =>
                AssignStmt(Access(Var(thisSymbol), s), expr)
            }
            constructor0.copy(
              body =
                groupInBlockSurface(thisSuperVars ++ lambdas ++ inits ++ stmts)
            )
          }

          val isAbstract = ms.isAbstract
          val staticMethods =
            if (isAbstract) staticMethods0 else constructor +: staticMethods0

          val staticVarIsConst = true

          val staticLambdas = asVector(map("staticLambdas"))
            .flatMap(parseGStmt)
            .asInstanceOf[Vector[FuncDef]]
          val staticMembers = staticLambdas ++ staticVars.map {
            case (vn, (ty, init)) =>
              VarDef(vn, ty, init, staticVarIsConst, ms.exportLevel)
          }.toVector ++ staticMethods

          Vector(
            // Wrap all static methods into a namespace,
            Namespace(name, BlockStmt(staticMembers), ms.exportLevel),
            ClassDef(
              name,
              tyVars,
              superType,
              instanceVars.toMap,
              instanceMethods,
              ms.exportLevel
            )
          )
        case "ImportStmt" =>
          val importString = StringContext.treatEscapes(asString(map("text")))
          ImportPattern.parseImports(importString).map(GImport)
        case "ExportStmt" =>
          val str = StringContext.treatEscapes(asString(map("text")))
          ExportPattern.parseExports(str).map(GExport)
        case "NamespaceAliasStmt" =>
          val name = Symbol(asString(map("name")))
          val rhsText = asString(map("rhs"))
          val rhs = if (rhsText.contains(".")) {
            rhsText.split(".").map(Symbol(_)).toVector
          } else {
            Vector(Symbol(rhsText))
          }
          Vector(NamespaceAliasStmt(name, rhs))
        case other => throw new Error(s"Unknown category: $other")
      }
    }

  private def parseGModule(v: Js.Val): GModule = {
    def removeSuffix(name: String, ext: String): String = {
      if (name.endsWith(ext)) name.dropRight(ext.length)
      else name
    }

    val obj = asObj(v)
    val name = asString(obj("name"))

    SimpleMath.withErrorMessage(s"Error when parsing module: $name") {
      def mergeInterfaces(stmts: Vector[GStmt]): Vector[GStmt] = {
        val aliases = mutable.HashMap[Symbol, TypeAliasStmt]()
        var stmts1 = Vector[GStmt]()
        stmts.foreach {
          case a: TypeAliasStmt if !aliases.contains(a.name) =>
            aliases(a.name) = a
          case a: TypeAliasStmt =>
            // merge interfaces
            val o1 = aliases(a.name).ty.asInstanceOf[ObjectType]
            val o2 = a.ty.asInstanceOf[ObjectType]
            aliases(a.name) =
              TypeAliasStmt(a.name, a.tyVars, o1.merge(o2), a.exportLevel)
          case BlockStmt(s2) =>
            stmts1 ++= mergeInterfaces(s2)
          case Namespace(symbol, block, exportLevel) =>
            val block1 = BlockStmt(mergeInterfaces(block.stmts))
            stmts1 :+= Namespace(symbol, block1, exportLevel)
          case other => stmts1 :+= other // I'm just lazy
        }
        aliases.values.toVector ++ stmts1
      }

      val stmts1 =
        SimpleMath.withErrorMessage(s"Error when parsing module: $name") {
          asVector(obj("stmts")).flatMap(parseGStmt)
        }
      val stmts2 = mergeInterfaces(stmts1)

      val modulePath = RelPath(
        Seq(".d.ts", ".tsx", ".ts").foldLeft(name)(
          (n, ext) => removeSuffix(n, ext)
        )
      )
      GModule(modulePath, stmts2, isDeclarationFileName(name))
    }
  }

  def isDeclarationFileName(name: String): Boolean = {
    name.endsWith(".d.ts")
  }

  import fastparse.JavaWhitespace._
  import fastparse.Parsed.{Failure, Success}
  import fastparse._
  import lambdanet.ImportStmt._

  object ImportPattern {
    sealed trait ImportClause
    case class Default(s: Symbol) extends ImportClause
    case class Singles(symbols: Vector[(Symbol, Symbol)]) extends ImportClause
    case class Module(newName: Symbol) extends ImportClause

    def unapply(v: Js.Val): Option[Vector[ImportStmt]] = {
      val map = asObj(v)
      asString(map("category")) match {
        case "ImportStmt" =>
          val importString = StringContext.treatEscapes(asString(map("text")))
          Some(parseImports(importString))
        case _ => None
      }
    }

    //  def spaceSep[_: P]: P[Unit] = P(CharsWhileIn(" \r\n", 1))

    def identifier[_: P]: P[String] = CharsWhileIn("a-zA-Z0-9$_").!

    def path[_: P]: P[ReferencePath] =
      P(JsonParsing.string | JsonParsing.singleQuoteString)
        .map { s =>
          val str = s.value
          val isRelative = str.startsWith(".")
          ReferencePath(RelPath(str), isRelative)
        }

    def parseImports(importText: String): Vector[ImportStmt] = {

      def importDefault[_: P]: P[Default] = P(identifier).map { name =>
        Default(Symbol(name))
      }

      def importModule[_: P]: P[Module] =
        P("*" ~/ "as" ~ identifier).map { name =>
          Module(Symbol(name))
        }

      def clause[_: P]: P[(String, Option[String])] =
        P(identifier ~ ("as" ~/ identifier).?)

      def importSingles[_: P]: P[Singles] =
        P("{" ~/ clause.rep(min = 0, sep = ",") ~ ",".? ~ "}")
          .map { clauses =>
            Singles(clauses.map {
              case (oldName, newNameOpt) =>
                val newName = Symbol(newNameOpt.getOrElse(oldName))
                (Symbol(oldName), newName)
            }.toVector)
          }

      def parseImportClause[_: P]: P[ImportClause] = {
        importSingles | importModule | importDefault
      }

      def parseImportEquals[_: P]: P[ImportStmt] = {
        P(
          "import" ~ identifier ~ "=" ~/ "require(" ~/ path ~ ")" ~ (";" | End)
        ).map {
          case (name, path: ReferencePath) =>
            ImportSingle(Symbol("$ExportEquals"), path, Symbol(name))
        }
      }

      def stmt[_: P]: P[Vector[ImportStmt]] = {
        def normalImports =
          P(
            "import" ~ parseImportClause
              .rep(min = 1, sep = ",") ~/ "from" ~ path ~ (";" | End)
          ).map {
            case (clauses, p) =>
              clauses.toVector.flatMap {
                case Default(s) => Vector(ImportDefault(p, s))
                case Singles(pairs) =>
                  pairs.map {
                    case (oldName, newName) => ImportSingle(oldName, p, newName)
                  }
                case Module(n) => Vector(ImportModule(p, n))
              }
          }
        def importForSideEffects =
          P("import" ~ path ~ (";" | End)).map(_ => Vector())

        parseImportEquals.map(Vector(_)) | normalImports | importForSideEffects
      }

      parse(importText, stmt(_)) match {
        case Success(value, _) => value
        case f: Failure =>
          throw new Error(
            s"Failed to parse import statement: '$importText', errors: ${f.trace().longMsg}"
          )
      }
    }
  }

  object ExportPattern {
    import lambdanet.ExportStmt._

    def unapply(v: Js.Val): Option[Vector[ExportStmt]] = {
      val map = asObj(v)
      asString(map("category")) match {
        case "ExportStmt" =>
          val str = StringContext.treatEscapes(asString(map("text")))
          Some(parseExports(str))
        //      case "TypeAliasStmt" =>
        //        val name = Symbol(asString(map("name")))
        //        val tVars = asVector(map("tyVars")).map(asSymbol)
        //        val `type` = ProgramParsing.parseType(map("type"))
        //        Some(Vector(ExportTypeAlias(name, tVars, `type`)))
        case _ => None
      }
    }

    def parseExports(str: String): Vector[ExportStmt] = {
      import ImportPattern.{identifier, path}

      type Creator = Option[ReferencePath] => Vector[ExportStmt]

      def exportDefault[_: P]: P[Creator] =
        P("{" ~ "default" ~/ ("as" ~/ identifier).? ~ "}").map {
          newNameOpt => p =>
            Vector(ExportDefault(newNameOpt.map(Symbol.apply), p))
        }

      def exportDefault2[_: P]: P[Creator] =
        P("default" ~ identifier)
          .map(n => p => Vector(ExportDefault(Some(Symbol(n)), p)))

      def exportSingles[_: P]: P[Creator] =
        P(
          "{" ~ (identifier ~/ ("as" ~/ identifier).?)
            .rep(min = 1, sep = ",") ~ ",".? ~ "}"
        ).map { clauses => p =>
          clauses.toVector.map {
            case (oldName, newNameOpt) =>
              ExportSingle(
                Symbol(oldName),
                Symbol(newNameOpt.getOrElse(oldName)),
                p
              )
          }
        }

      def exportFromOther[_: P]: P[Creator] = {
        P("*").map(
          _ =>
            p => {
              Vector(ExportOtherModule(p.get))
            }
        )
      }

      def exportNamespace[_: P]: P[Vector[ExportStmt]] =
        P(
          "export" ~ "as" ~ "namespace" ~/ identifier
        ).map { name =>
          val s = Symbol(name)
          Vector(ExportSingle(s, s, None))
        }

//      def exportAssign[_: P] =
//        P("export" ~ "=" ~/ identifier).map { name =>
//          Vector(ExportDefault(name, None))
//        }

      def exportRest[_: P] =
        P(
          "export" ~/ (exportFromOther | exportDefault | exportDefault2 | exportSingles)
            ~ ("from" ~/ path).?
        ).map { case (creator, p) => creator(p) }

      def stmt[_: P]: P[Vector[ExportStmt]] =
        (exportNamespace | exportRest) ~ ";".?

      parse(str, stmt(_)) match {
        case Success(value, _) => value
        case f: Failure =>
          throw new Error(
            s"Failed to parse export statement: '$str', errors: ${f.trace().longMsg}"
          )
      }
    }
  }

  def main(args: Array[String]): Unit = {
    val jValue = parseJsonFromFile(
      pwd / up / 'DeepTypeTS / 'output / "foo.json"
    )
    //    println(jValue)

    asArray(jValue).map(parseGStmt).foreach(println)
  }
}
