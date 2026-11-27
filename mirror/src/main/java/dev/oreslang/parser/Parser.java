package dev.oreslang.parser;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.StaticProxySynthesis;

import java.util.ArrayList;
import java.util.List;

import static dev.oreslang.parser.Token.Type.*;

public final class Parser {
    public static final String ROOT_MODULE = "__root__";
    // '$' cannot appear in source identifiers; only parser can mint these namespaces.
    public static final String PKG_ROOT_NAMESPACE = "$pkg_root$";
    public static final String IMPORTS_NAMESPACE = "$imports$";
    /** Marker for `use default namespace(n);`, resolved against the source path. */
    public static final String DERIVED_NAMESPACE_PREFIX = "$default$";

    public enum AttoCallableVersion { V1, V2 }

    public record SyntaxWarning(int line, int column, String message) { }

    public record ParseResult(Ast.Program program, List<SyntaxWarning> warnings) {
        public ParseResult {
            warnings = List.copyOf(warnings);
        }
    }

    private final List<Token> tokens;
    private final boolean singletonCheckOnly;
    /** Compiler-only static proxy requests; no runtime reflection descriptors. */
    private final java.util.Set<String> staticProxyTargets = new java.util.LinkedHashSet<>();
    private final List<StaticProxySynthesis.InterceptorRequest> staticProxyInterceptors =
            new ArrayList<>();
    // Null preserves the shipping Oreslang parser's historical behavior.
    private final AttoCallableVersion attoCallableVersion;
    /** Canonical AttoLang source rules, never guessed from a legacy filename. */
    private final boolean canonicalAttoSource;
    private final List<SyntaxWarning> warnings = new ArrayList<>();
    private static final java.util.Set<String> BUILTIN_OF_TYPES =
            java.util.Set.of("Map", "Array", "Tuple", "Record");
    private static final java.util.Set<String> PENDING_OF_TYPES =
            java.util.Set.of("Entry", "Pair", "List", "Vector", "Set");
    private static final java.util.Set<String> COLLECTION_OF_TYPES =
            java.util.Set.of("Map", "Array", "Tuple", "Record",
                    "Entry", "Pair", "List", "Vector", "Set");
    // Conservative module-wide lexical collision gate. A factory rewrite may
    // not capture a user class, import alias, parameter or local binding.
    // Global conservatism is deliberate until typed-HIR symbol ownership is
    // available: preserving ordinary CallExpr beats inventing guest authority.
    private final java.util.Set<String> shadowedOfTypes = new java.util.HashSet<>();
    private int current;
    // Exact token index of the standalone subject's closing single bar.
    private int delimitedSubjectEnd = -1;
    // A long chain of contextual ownership prefixes must not exhaust the JVM
    // call stack before the compiler can issue a deterministic parse error.
    // This budget also covers other recursive unary and parenthesized forms.
    private static final int MAX_UNARY_NESTING = 128;
    private int unaryNesting;
    private boolean suppressRefinementOperators;
    /** Lexical names recognized as local aggregate constructors; never runtime type factories. */
    private final java.util.ArrayDeque<java.util.Set<String>> localTypeScopes =
            new java.util.ArrayDeque<>();
    // A match guard's outer '->' delimits an arm, not a lambda that happens
    // to follow a parenthesized guard expression.
    private int matchGuardStartToken = -1;

    // Forward type signatures are compile-time-only and intentionally absent
    // from executable ASTs. The v1 contract requires an exact same-scope
    // definition; cross-file and unresolved linker stubs fail closed.
    private record ForwardType(Token.Type kind, String name, Ast.Visibility visibility,
                               List<String> generics) { }
    // Identity-bound parser provenance, not a spelling-based name allowlist.
    private final java.util.Set<Ast.Decl> explicitlyPrivate =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private <T extends Ast.Decl> T recordVisibility(T declaration, Modifiers modifiers) {
        if (modifiers.visibilitySeen && modifiers.visibility == Ast.Visibility.PRIVATE)
            explicitlyPrivate.add(declaration);
        return declaration;
    }
    private final List<ForwardType> rootForwards = new ArrayList<>();
    private List<ForwardType> moduleForwards;
    public Parser(List<Token> tokens) {
        this(tokens, null, false, false);
    }

    private Parser(List<Token> tokens, AttoCallableVersion version) {
        this(tokens, version, true, false);
    }

    private Parser(List<Token> tokens, boolean singletonCheckOnly) {
        this(tokens, null, false, singletonCheckOnly);
    }

    private Parser(List<Token> tokens, AttoCallableVersion version, boolean canonicalAttoSource,
                   boolean singletonCheckOnly) {
        this.singletonCheckOnly = singletonCheckOnly;
        this.attoCallableVersion = version;
        this.canonicalAttoSource = canonicalAttoSource;
        this.tokens = List.copyOf(tokens);
        // A public parser must not trust an externally supplied Token stream to
        // have come from Lexer. In particular, a synthesized IDENT("unsafe")
        // must not bypass the same G0 admission gate as ordinary source text.
        // Literal strings, template text and #unsafe symbols are data, not
        // executable identifier tokens.
        for (Token token : this.tokens) {
            if (token.type() == UNSAFE ||
                    (token.type() == IDENT && "unsafe".equals(token.lexeme()))) {
                throw error(token, "unsafe is reserved until checked unsafe effects and backend lowering exist");
            }
        }
        // Read only lexer-produced token kinds, never source substrings.
        // This scans declarations forward so a later nominal Tuple does not
        // retroactively become an unowned compiler Tuple.of constructor.
        for (int i = 0; i < this.tokens.size(); i++) {
            Token token = this.tokens.get(i);
            if (token.type() != IDENT || !COLLECTION_OF_TYPES.contains(token.lexeme())) continue;
            Token.Type prior = i == 0 ? EOF : this.tokens.get(i - 1).type();
            Token.Type next = i + 1 == this.tokens.size()
                    ? EOF : this.tokens.get(i + 1).type();
            boolean nominal = prior == CLASS || prior == STRUCT || prior == MODULE
                    || prior == TYPE || prior == TYPES || prior == ACTOR
                    || prior == ISOACTOR || prior == CONTRACT
                    || prior == INTERFACE || prior == TRAIT;
            boolean importAlias = prior == AS && next == FROM;
            boolean callableName = (prior == FNC || prior == ROUTINE)
                    && next == LPAREN;
            boolean bindingName = (next == EQUAL || next == COLON)
                    && (prior == VAL || prior == CONST || prior == LET
                        || prior == MUT || prior == IDENT);
            boolean formalName = (next == COMMA || next == RPAREN
                    || next == PIPE) && (prior == IDENT || prior == PIPE);
            // Only *declaration* type parameters shadow canonical factories.
            // A type reference such as Result<Map> or Map<string, Tuple> is
            // NOT a declaration of Map/Tuple; scanning '<Foo>' substrings
            // without tracking the declaration owner incorrectly suppresses
            // compiler-owned .of calls elsewhere in the compilation unit.
            boolean genericFormalCandidate = isDeclarationGenericParameter(i);
            boolean forBindingName = next == OF
                    && (prior == CONST || prior == LET || prior == VAL);
            boolean contextualTypeAlias = prior == IDENT
                    && this.tokens.get(i - 1).lexeme().equals("alias")
                    && i > 1 && this.tokens.get(i - 2).type() == TYPE;
            if (nominal || importAlias || callableName || bindingName
                    || formalName || genericFormalCandidate || forBindingName
                    || contextualTypeAlias) {
                shadowedOfTypes.add(token.lexeme());
            }
        }
    }

    /**
     * Distinguish a declaration's generic *formal* from an applied type
     * argument. This remains a conservative lexical safeguard, not final
     * symbol resolution. Only recognizable declaration heads may hide the
     * intrinsic owner; unrelated uses of '<Map>' or '<Tuple>' must not.
     */
    private boolean isDeclarationGenericParameter(int tokenIndex) {
        // Only a formal identifier immediately after '<' or a parameter
        // separator can declare/shadow a compiler-owned factory name.
        // A default type reference in 'fnc build<T = Tuple>()' is a use,
        // not a declaration of Tuple; nested defaults behave likewise.
        if (tokenIndex == 0) return false;
        Token.Type preceding = tokens.get(tokenIndex - 1).type();
        if (preceding != LT && preceding != COMMA) return false;
        int depth = 0;
        int opening = -1;
        for (int j = tokenIndex - 1; j >= 0; j--) {
            Token.Type t = tokens.get(j).type();
            if (t == GT) {
                depth++;
            } else if (t == LT) {
                if (depth == 0) {
                    opening = j;
                    break;
                }
                depth--;
            } else if (depth == 0 && (t == SEMICOLON || t == LBRACE
                    || t == RBRACE || t == LPAREN || t == RPAREN)) {
                return false;
            }
        }
        if (opening < 2 || tokens.get(opening - 1).type() != IDENT) return false;
        Token.Type head = tokens.get(opening - 2).type();
        return head == FNC || head == ROUTINE || head == CLASS
                || head == STRUCT || head == ACTOR || head == ISOACTOR
                || head == INTERFACE || head == TRAIT || head == MODULE
                || head == TYPE || head == TYPES || head == CONTRACT;
    }

    public static Ast.Program parse(String source) {
        return new Parser(new Lexer(source).scan()).parseProgram();
    }

    /** Explicit draft v1/v2 source grammar. This is a real parser entrypoint,
     * not a completed package-edition/Truffle guest integration. Unsupported
     * compound/effectful comma body shapes fail before AST emission. */
    public static Ast.Program parseVersionedAtto(String source, int version) {
        if (version != 1 && version != 2)
            throw new IllegalArgumentException("E_LANGUAGE_VERSION: expected 1 or 2");
        return new Parser(new Lexer(source).scan(),
                version == 1 ? AttoCallableVersion.V1 : AttoCallableVersion.V2)
                .parseProgram();
    }

    /**
     * Narrow compiler-only query parser for the first closed-world AOT
     * reflection metadata bootstrap. This reuses the production expression
     * parser but does NOT admit a guest runtime type_of<T>() intrinsic.
     *
     * The caller must independently bind the returned nominal TypeRef against
     * a fully typechecked source program and a verified package identity.
     */
    public static Ast.TypeRef parseStaticTypeOfQueryV1(String sourceExpression) {
        if (sourceExpression == null || sourceExpression.isBlank()
                || sourceExpression.length() > 512) {
            throw new IllegalArgumentException("bounded type_of<T>() query required");
        }
        Parser parser = new Parser(new Lexer(sourceExpression).scan());
        Ast.Expr expression = parser.parseExpression();
        parser.consume(EOF, "expected end of static type_of query");
        if (!(expression instanceof Ast.CallExpr call)
                || call.origin() != Ast.CallOrigin.DIRECT
                || !(call.callee() instanceof Ast.NameExpr callee)
                || !callee.name().equals("type_of")
                || !call.typeArgumentsPresent()
                || call.typeArguments().size() != 1
                || !call.arguments().isEmpty()) {
            throw new IllegalArgumentException(
                    "static reflection query must be exactly type_of<Nominal>()");
        }
        Ast.TypeRef nominal = call.typeArguments().getFirst();
        if (nominal.inferArguments() || !nominal.arguments().isEmpty()
                || !nominal.name().matches("[A-Za-z_][A-Za-z0-9_]*")
                || nominal.name().startsWith("_")) {
            throw new IllegalArgumentException(
                    "static reflection v1 requires one closed public nominal type");
        }
        return nominal;
    }

    /**
     * TEST/CHECK ONLY: parse singleton ASTs without making them executable.
     * The normal parser and runtime entry points continue to fail closed.
     */
    public static Ast.Program parseSingletonsCheckOnly(String source) {
        // The normal parser interns Symbol process literals while building the
        // AST. A subsequently rejected check-only singleton must not consume
        // permanent domain Symbol capacity. Scan all tokens before parsing.
        List<Token> scanned = new Lexer(source).scan();
        for (Token token : scanned) {
            if (token.type() == SYMBOL_LITERAL) {
                throw new IllegalArgumentException(
                        "ORES-SINGLETON-CHECKONLY: Symbol literals cannot be "
                                + "interned during singleton check-only admission at "
                                + token.line() + ":" + token.column());
            }
        }
        return new Parser(scanned, true).parseProgram();
    }

    /**
     * Explicit opt-in strict AttoLang v1 syntax admission. This is not inferred
     * from the filename: existing .ores and .atto legacy callers remain intact.
     * Fail before AST lowering on C-style equality or opaque template payloads,
     * which would otherwise be reparsed outside the selected source profile.
     */
    public static Ast.Program parseStrictAtto(String source) {
        List<Token> scanned = new Lexer(source).scan();
        for (Token token : scanned) {
            // Java currently has no source-level typed Error identity or
            // ordinary throw lowering. Legacy trap converts selected JVM
            // exceptions into sanitized text: that is NOT the normative
            // trap-to-Result guest contract. Reject strict source admission
            // before creating any checked AST until Error/throw is implemented.
            if (token.type() == TRAP || token.type() == THROW) {
                throw new IllegalArgumentException("ATTO_STRICT_TYPED_TRAP_UNSUPPORTED at "
                        + token.line() + ":" + token.column()
                        + ": source trap/throw requires checked Error identity and ordinary throw lowering");
            }
            if (token.type() == EQUAL_EQUAL || token.type() == BANG_EQUAL) {
                throw new IllegalArgumentException("ATTO_STRICT_INVALID_EQUALITY_TOKEN at "
                        + token.line() + ":" + token.column() + ": use eq / neq / !eq");
            }
            if (token.type() == TEMPLATE_LITERAL) {
                throw new IllegalArgumentException("ATTO_STRICT_TEMPLATE_UNSUPPORTED at "
                        + token.line() + ":" + token.column()
                        + ": template interpolation requires profile-preserving typed lowering");
            }
        }
        return new Parser(scanned, null, true, false).parseProgram();
    }


    /**
     * Parse one entire interpolation as an expression, never a statement.
     * Nested cpu/gpu domains cannot bypass the compilation-unit preflight.
     */
    static Ast.Expr parseInterpolatedExpression(String source) {
        Parser parser = new Parser(new Lexer(source).scan());
        for (Token token : parser.tokens) {
            if (token.type() == UNSAFE) {
                throw parser.error(token, "unsafe is reserved until checked unsafe effects and backend lowering exist");
            }
            if (token.type() == GPU || token.type() == CPU || token.type() == QUANTUM) {
                throw parser.error(token, "execution domain requires checked backend lowering");
            }
        }
        Ast.Expr result = parser.parseExpression();
        parser.consume(EOF, "expected end of template interpolation expression");
        return result;
    }

    public static ParseResult parseWithWarnings(String source) {
        Parser parser = new Parser(new Lexer(source).scan());
        Ast.Program program = parser.parseProgram();
        return new ParseResult(program, parser.warnings);
    }

    public Ast.Program parseProgram() {
        // CPU/GPU Task v2 has a real source AST. Keep unsupported synchronous
        // execution regions and quantum fail-closed, but allow canonical task
        // forms (plus legacy nb aliases) to reach typed lowering. GPU task is
        // deliberately rejected later until a physical device provider exists.
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type() == UNSAFE) {
                throw error(token, "unsafe is reserved until checked unsafe effects and backend lowering exist");
            }
            if (token.type() == QUANTUM) {
                throw error(token, "execution domain 'quantum' requires explicit backend lowering; no implicit CPU fallback");
            }
            // GPU actor heads are declarations, NOT CPU-executable GPU
            // expressions. Preserve a distinct, non-executable protected AST
            // so no ordinary actor lowering can erase the GPU execution domain.
            boolean gpuActorHead = token.type() == GPU
                    && i + 1 < tokens.size()
                    && tokens.get(i + 1).type() == ACTOR;
            if (token.type() == CPU || token.type() == GPU) {
                boolean canonicalTask =
                        i + 2 < tokens.size()
                                && tokens.get(i + 1).type() == IDENT
                                && tokens.get(i + 1).lexeme().equals("task")
                                && tokens.get(i + 2).type() == LBRACE;
                boolean legacyNbTask =
                        i > 0 && tokens.get(i - 1).type() == NB
                                && i + 1 < tokens.size()
                                && tokens.get(i + 1).type() == LBRACE;
                if (!canonicalTask && !legacyNbTask && !gpuActorHead) {
                    throw error(token, "synchronous execution domain '" + token.lexeme()
                            + "' { ... } is reserved but not production-lowered yet;"
                            + " use '" + token.lexeme() + " task { ... }' for asynchronous work;"
                            + " no implicit CPU fallback");
                }
            }
        }
        // Atto's canonical first-line header is "use namespace foo;".
        // "use" is intentionally contextual (IDENT), not a new global keyword.
        // Preserve the older "namespace foo;" header with identical AST identity.
        String namespace = null;
        boolean useNamespaceHeader = isContextualIdentifier("use");
        boolean derivedNamespace = false;
        if (useNamespaceHeader) {
            advance();
            derivedNamespace = match(DEFAULT);
            consume(NAMESPACE, "expected 'namespace' after file-root 'use'");
        }
        if (derivedNamespace) {
            // `use default namespace;` / `use default namespace(n);` derives
            // the namespace from the last n folders of the package-relative
            // source path. The parser has no path, so it records the depth and
            // the compiler resolves it (DERIVED_NAMESPACE_PREFIX + n).
            int depth = 1;
            if (match(LPAREN)) {
                Token count = consume(INT, "expected a namespace folder depth from 1 to 32");
                try {
                    depth = Integer.parseInt(count.lexeme());
                } catch (NumberFormatException invalid) {
                    throw error(count, "namespace folder depth must be an integer from 1 to 32");
                }
                if (depth < 1 || depth > 32) {
                    throw error(count, "namespace folder depth must be an integer from 1 to 32");
                }
                consume(RPAREN, "expected ')' after namespace folder depth");
            }
            namespace = DERIVED_NAMESPACE_PREFIX + depth;
            consume(SEMICOLON, "namespace declaration must end with ';'");
        } else if (useNamespaceHeader || match(NAMESPACE)) {
            // Namespace names are dotted (commerce.storefront); file paths use
            // '/' and appear only inside import strings.
            StringBuilder dotted = new StringBuilder();
            do {
                String segment = consume(IDENT, "expected namespace name").lexeme();
                if (ROOT_MODULE.equals(segment)) {
                    throw error(previous(), "namespace name '" + ROOT_MODULE + "' is reserved for file-root declarations");
                }
                if (dotted.length() > 0) dotted.append('.');
                dotted.append(segment);
            } while (match(DOT));
            namespace = dotted.toString();
            consume(SEMICOLON, "namespace declaration must end with ';'");
        }

        List<Ast.ImportDecl> imports = new ArrayList<>();
        List<Ast.ExportDecl> exports = new ArrayList<>();
        // File exports can appear before imports as well as after them.
        while (true) {
            if (match(IMPORT)) imports.add(parseImport());
            else if (match(EXPORT)) exports.add(parseExport());
            else break;
        }

        // Once a package namespace participates, require unique local import
        // bindings across *all* import forms, including legacy selections.
        // The later verified linker must additionally check top-level
        // declarations and declared public exports before guest lowering.
        if (imports.stream().anyMatch(i -> i.path().startsWith(UNLINKED_PACKAGE_PREFIX))) {
            var boundNames = new java.util.HashSet<String>();
            for (Ast.ImportDecl imported : imports) {
                for (String name : dev.oreslang.imports.ImportRules.exposedBindings(imported)) {
                    if (!boundNames.add(name)) {
                        throw error(previous(), "duplicate local import binding '" + name + "'");
                    }
                }
            }
        }

        // Use parser-resolved import selections as an additional identity
        // boundary: grouped imports and local aliases must not be captured
        // by a syntactic Type.of rewrite, even when token lookbehind misses
        // their brace-delimited declaration form.
        for (Ast.ImportDecl imported : imports) {
            for (String name : imported.names()) {
                if (COLLECTION_OF_TYPES.contains(name)) shadowedOfTypes.add(name);
            }
            if (imported.namespace() != null
                    && COLLECTION_OF_TYPES.contains(imported.namespace())) {
                shadowedOfTypes.add(imported.namespace());
            }
        }

        List<Ast.ModuleDecl> modules = new ArrayList<>();
        List<Ast.Decl> rootDeclarations = new ArrayList<>();

        while (!check(EOF)) {
            rejectUnsupportedAcceleratorSyntax();
            if (match(EXPORT)) {
                exports.add(parseExport());
                continue;
            }
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers modifiers = parseModifiers();

            if (isContextualIdentifier("declare")) {
                rootForwards.add(parseForwardType(annotations, modifiers));
                continue;
            }

            if (match(CLASS)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                // 'define' is optional; the old 'class ... define' order remains accepted.
                if (match(DEFINE)) modifiers = mergeModifiers(modifiers, parseModifiers());
                modifiers = mergeModifiers(modifiers, parseModifiers());
                rejectCallableStructuralAnnotation(annotations, "class declarations");
                validateCopyPolicyClassAnnotations(annotations);
                validateClassModifiers(modifiers);
                rootDeclarations.add(recordVisibility(parseClass(modifiers.visibility, modifiers.isAbstract, modifiers.visibility != Ast.Visibility.PUBLIC, annotations), modifiers));
                continue;
            }

            if (check(CONTRACT) && defineFollowsAfterVisibilityModifiers()) {
                advance();
                modifiers = mergeModifiers(modifiers, parseModifiers());
                consume(DEFINE, "compatibility contract declaration requires both 'contract' and 'define'");
                modifiers = mergeModifiers(modifiers, parseModifiers());
                validateOnlyVisibilityModifiers(modifiers, "contracts");
                rejectCallableStructuralAnnotation(annotations, "contract declarations");
                rejectDiscardedDeclarationAnnotations(annotations, "contract declarations");
                rootDeclarations.add(recordVisibility(parseContract(modifiers.visibility), modifiers));
                continue;
            }

            if (check(INTERFACE) && defineFollowsAfterVisibilityModifiers()) {
                advance();
                modifiers = mergeModifiers(modifiers, parseModifiers());
                consume(DEFINE, "compatibility interface declaration requires both 'interface' and 'define'");
                modifiers = mergeModifiers(modifiers, parseModifiers());
                validateOnlyVisibilityModifiers(modifiers, "interfaces");
                rejectCallableStructuralAnnotation(annotations, "interface declarations");
                rejectDiscardedDeclarationAnnotations(annotations, "interface declarations");
                rootDeclarations.add(recordVisibility(parseDefinedInterface(modifiers.visibility, INTERFACE), modifiers));
                continue;
            }

            if (match(DEFINE)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                if (isContextualIdentifier("singleton")) {
                    Token keyword = advance();
                    if (!singletonCheckOnly) throw unsupportedSingleton(keyword);
                    rootDeclarations.add(parseSingleton(modifiers, annotations, keyword));
                    continue;
                }
                if (isExternalActorMethodHead()) {
                    rootDeclarations.add(parseExternalActorMethod(annotations, modifiers));
                    continue;
                }
                if (isProtectedDeclarationHead()) {
                    rootDeclarations.add(parseProtectedDeclaration(modifiers, annotations));
                    continue;
                }
                rejectUnloweredRuntimeEntityDeclaration();
                ActorPrefix actorPrefix = parseActorPrefix();
                if (match(ACTOR, ISOACTOR)) {
                    rootDeclarations.add(parseDefinedActorClass(previous(), modifiers, annotations, actorPrefix));
                    continue;
                }
                if (modifiers.shared) {
                    throw error(previous(), "'shared' must modify an actor declaration; use 'shared actor <Name>'");
                }
                if (match(MODULE)) {
                    if (modifiers.visibilitySeen || modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural || modifiers.nonLexical || modifiers.trapped
                            || modifiers.isStatic || modifiers.isAbstract || modifiers.shared || modifiers.untrusted) {
                        throw error(previous(), "modules do not accept declaration modifiers");
                    }
                    rejectCallableStructuralAnnotation(annotations, "module declarations");
                    modules.add(parseModule(annotations));
                    continue;
                }
                if (match(CONTRACT)) {
                    modifiers = mergeModifiers(modifiers, parseModifiers());
                    validateOnlyVisibilityModifiers(modifiers, "contracts");
                    rejectCallableStructuralAnnotation(annotations, "contract declarations");
                    rejectDiscardedDeclarationAnnotations(annotations, "contract declarations");
                    rootDeclarations.add(recordVisibility(parseContract(modifiers.visibility), modifiers));
                    continue;
                }
                if (match(CLASS)) {
                    modifiers = mergeModifiers(modifiers, parseModifiers());
                    rejectCallableStructuralAnnotation(annotations, "class declarations");
                    validateCopyPolicyClassAnnotations(annotations);
                    validateClassModifiers(modifiers);
                    rootDeclarations.add(recordVisibility(parseClass(modifiers.visibility, modifiers.isAbstract, modifiers.visibility != Ast.Visibility.PUBLIC, annotations), modifiers));
                    continue;
                }
                if (match(INTERFACE, TRAIT)) {
                    Token.Type declarationKind = previous().type();
                    modifiers = mergeModifiers(modifiers, parseModifiers());
                    validateOnlyVisibilityModifiers(modifiers, "interfaces/traits");
                    rejectCallableStructuralAnnotation(annotations, "interface declarations");
                    rejectDiscardedDeclarationAnnotations(annotations, "interface declarations");
                    rootDeclarations.add(recordVisibility(parseDefinedInterface(modifiers.visibility, declarationKind), modifiers));
                    continue;
                }
                if (match(TYPE)) {
                    modifiers = mergeModifiers(modifiers, parseModifiers());
                    validateOnlyVisibilityModifiers(modifiers, "type aliases");
                    rejectCallableStructuralAnnotation(annotations, "type aliases");
                    rejectDiscardedDeclarationAnnotations(annotations, "type aliases");
                    rootDeclarations.add(recordVisibility(parseTypeAlias(modifiers.visibility), modifiers));
                    continue;
                }
                if (hasDirectStructAttachment()) {
                    validateOnlyVisibilityModifiers(modifiers, "attached struct declarations");
                    rejectCallableStructuralAnnotation(annotations, "attached struct declarations");
                    rejectDiscardedDeclarationAnnotations(annotations, "attached struct declarations");
                    rootDeclarations.add(parseDirectStructAttachment(modifiers.visibility));
                    continue;
                }
                // Compatibility: both `pub define fnc` and `define pub fnc`
                // denote the same file-root callable, with identical modifiers.
                if (check(FNC) || check(ROUTINE) || looksLikeReturnFirstCallableHead()) {
                    Ast.Decl callable = parseDeclarationAfterModifiers(annotations, modifiers);
                    if (callable != null) {
                        rootDeclarations.add(callable);
                        continue;
                    }
                }
                rejectUnimplementedInvertedAggregate();
                throw error(previous(), "expected module, class, interface, trait, type, fnc, or routine after 'define'");
            }

            Ast.Decl declaration = parseDeclarationAfterModifiers(annotations, modifiers);
            if (declaration == null) throw error(peek(), "expected module or top-level declaration");
            rootDeclarations.add(declaration);
        }

        validateForwardTypes(rootForwards, rootDeclarations, "file root");
        validateExports(exports, rootDeclarations);
        if (!rootDeclarations.isEmpty()) {
            modules.add(new Ast.ModuleDecl(ROOT_MODULE, List.of(), List.of(), rootDeclarations));
        }
        if (modules.isEmpty()) throw error(peek(), "a source file must define at least one module or top-level declaration");
        return StaticProxySynthesis.expand(
                new Ast.Program(namespace, imports, modules, exports),
                staticProxyTargets, staticProxyInterceptors);
    }

    /** `export pub Foo;` and `export pub[pkg] class Bar;` are file-level declarations. */
    private Ast.ExportDecl parseExport() {
        Token at = previous();
        consume(PUB, "export requires 'pub' visibility");
        Ast.ExportScope scope = Ast.ExportScope.PUBLIC;
        if (match(LBRACKET)) {
            Token value = consume(IDENT, "expected pkg in pub[pkg]");
            if (!"pkg".equals(value.lexeme()))
                throw error(value, "only pub[pkg] export visibility is supported");
            consume(RBRACKET, "expected ']' after pub[pkg]");
            scope = Ast.ExportScope.PACKAGE;
        }
        Ast.ExportKind kind = null;
        if (match(CLASS)) kind = Ast.ExportKind.CLASS;
        else if (match(FNC)) kind = Ast.ExportKind.FNC;
        else if (match(ROUTINE)) kind = Ast.ExportKind.ROUTINE;
        else if (match(ACTOR)) kind = Ast.ExportKind.ACTOR;
        else if (match(INTERFACE)) kind = Ast.ExportKind.INTERFACE;
        else if (match(TRAIT)) kind = Ast.ExportKind.TRAIT;
        else if (match(TYPE)) kind = Ast.ExportKind.TYPE;
        String name = consume(IDENT, "expected a declaration name after export").lexeme();
        consume(SEMICOLON, "export declaration requires ';'");
        return new Ast.ExportDecl(name, scope, kind);
    }

    /** Validate after all declarations, so placement never changes binding. */
    private void validateExports(List<Ast.ExportDecl> exports, List<Ast.Decl> declarations) {
        java.util.Map<String, Ast.ExportDecl> resolved = new java.util.LinkedHashMap<>();
        for (Ast.ExportDecl entry : exports) {
            Ast.ExportKind kind = null;
            for (Ast.Decl decl : declarations) {
                Ast.ExportKind found = null;
                Ast.Visibility visibility = Ast.Visibility.PRIVATE;
                String name = null;
                if (decl instanceof Ast.ClassDecl c) {
                    name = c.name(); visibility = c.visibility();
                    found = c.actorKind() == Ast.ActorKind.NONE
                            ? Ast.ExportKind.CLASS : Ast.ExportKind.ACTOR;
                } else if (decl instanceof Ast.FunctionDecl f) {
                    name = f.name(); visibility = f.visibility();
                    found = f.kind() == Ast.CallableKind.FNC
                            ? Ast.ExportKind.FNC : Ast.ExportKind.ROUTINE;
                } else if (decl instanceof Ast.TypeAliasDecl t) {
                    name = t.name(); visibility = t.visibility(); found = Ast.ExportKind.TYPE;
                } else if (decl instanceof Ast.InterfaceDecl i) {
                    name = i.name(); visibility = i.visibility();
                    found = i.declarationKind() == Ast.InterfaceKind.TRAIT
                            ? Ast.ExportKind.TRAIT : Ast.ExportKind.INTERFACE;
                }
                if (!entry.name().equals(name)) continue;
                if (explicitlyPrivate.contains(decl))
                    throw error(previous(), "explicit private declaration cannot be exported: " + entry.name());
                if (kind != null && kind != found)
                    throw error(previous(), "ambiguous export target: " + entry.name());
                if (entry.scope() == Ast.ExportScope.PACKAGE && visibility == Ast.Visibility.PUBLIC)
                    throw error(previous(), "pub[pkg] export conflicts with public declaration: " + entry.name());
                kind = found;
            }
            if (kind == null)
                throw error(previous(), "export references undeclared file-root symbol: " + entry.name());
            if (entry.kind() != null && entry.kind() != kind)
                throw error(previous(), "export declaration kind mismatch for " + entry.name());
            Ast.ExportDecl prior = resolved.get(entry.name());
            if (prior == null) {
                resolved.put(entry.name(), entry);
            } else {
                if (prior.scope() != entry.scope())
                    throw error(previous(), "conflicting export visibility: " + entry.name());
                // Every directive has independently passed the declaration-kind
                // check. Retain an explicit kind constraint in either order.
                if (prior.kind() == null && entry.kind() != null)
                    resolved.put(entry.name(), entry);
            }
        }
        exports.clear();
        exports.addAll(resolved.values());
    }

    private static final String UNLINKED_PACKAGE_PREFIX = "pkg:";

    private String packageImportPath(String identity) {
        // Separate package identity from all relative-file and Java host imports.
        // Until the package graph linker is implemented, ImportRules rejects
        // this tag before any filesystem or host-class resolution.
        if (identity == null || identity.isBlank() || identity.length() > 4096
                || identity.contains("..") || identity.endsWith(".")
                || identity.indexOf('\\') >= 0
                || identity.chars().anyMatch(Character::isISOControl)) {
            throw error(previous(), "invalid package identity in import");
        }
        return UNLINKED_PACKAGE_PREFIX + identity;
    }

    private String implicitPackageNamespace(String identity) {
        String name = identity.substring(identity.lastIndexOf('.') + 1);
        // Use the actual Atto lexer to reject reserved words instead of
        // duplicating an incomplete keyword table in the import parser.
        List<Token> candidate = new Lexer(name).scan();
        if (candidate.size() != 2 || candidate.getFirst().type() != IDENT
                || ROOT_MODULE.equals(name) || "pkg".equals(name)) {
            throw error(previous(), "package import requires explicit 'as <namespace>'");
        }
        return name;
    }

    private Ast.ImportDecl parseImport() {
        // Backend-qualified sugar for the existing capability-gated class import.
        // Produce the identical AST as: import class Math as M from "java:java.lang.Math".
        // No host class is resolved or initialized by this parser.
        if (check(IDENT) && "jvm".equals(peek().lexeme())) {
            advance();
            String binaryName = consume(STRING, "expected quoted JVM class name").lexeme();
            consume(AS, "JVM import requires 'as <alias>'");
            String alias = consume(IDENT, "expected JVM class alias").lexeme();
            consume(SEMICOLON, "expected ';' after JVM import");
            String path = "java:" + binaryName;
            String sourceName = dev.oreslang.imports.ImportRules.javaSimpleName(path);
            return new Ast.ImportDecl(Ast.ImportKind.CLASS,
                    List.of(sourceName), false, alias, path);
        }
        if (check(IDENT) && "dotnet".equals(peek().lexeme())) {
            throw error(peek(), "dotnet host imports require the .NET backend");
        }
        // import pkg 'z'; is a namespace binding, not a side-effect-only
        // import and not a source-file namespace declaration.
        if (check(IDENT) && "pkg".equals(peek().lexeme())) {
            advance();
            String identity = consume(STRING, "expected quoted package identity").lexeme();
            String namespace = match(AS)
                    ? consumeImportAlias("expected package namespace alias")
                    : implicitPackageNamespace(identity);
            consume(SEMICOLON, "expected ';' after package import");
            return new Ast.ImportDecl(Ast.ImportKind.ALL, List.of(), true,
                    namespace, packageImportPath(identity));
        }

        Ast.ImportKind kind;
        List<String> names = new ArrayList<>();
        boolean wildcard = false;
        String namespace = null;

        if (match(STAR)) {
            kind = Ast.ImportKind.ALL;
            wildcard = true;
            consume(AS, "'import *' requires 'as <namespace>'");
            namespace = consumeImportAlias("expected import namespace");
        } else {
            if (isLegacyFnSpelling()) {
                throw error(peek(), "function imports use 'import fnc', not 'import fn'");
            }
            if (match(MODULE)) kind = Ast.ImportKind.MODULE;
            else if (match(ACTOR)) kind = Ast.ImportKind.ACTOR;
            else if (match(CLASS)) kind = Ast.ImportKind.CLASS;
            else if (match(FNC)) kind = Ast.ImportKind.FUNCTION;
            else if (match(INTERFACE)) kind = Ast.ImportKind.INTERFACE;
            else if (match(CONTRACT)) kind = Ast.ImportKind.CONTRACT;
            else if (match(TRAIT)) kind = Ast.ImportKind.TRAIT;
            else if (match(STRUCT)) kind = Ast.ImportKind.STRUCT;
            else if (match(TYPE)) kind = Ast.ImportKind.TYPE;
            else if (match(TYPES)) kind = Ast.ImportKind.TYPES;
            else throw error(peek(), "expected module, actor, class, fnc, interface, contract, trait, struct, type, types, or * after import");

            if (match(STAR)) {
                wildcard = true;
                consume(AS, "wildcard import requires 'as <namespace>'");
                namespace = consumeImportAlias("expected import namespace");
            } else {
                parseImportSelection(kind, names);
            }

            if (!wildcard && match(AS)) {
                if (names.size() != 1) {
                    throw error(previous(), "named import aliases require exactly one selected name");
                }
                namespace = consumeImportAlias("expected import alias");
            }
        }

        consume(FROM, "expected 'from' in import");
        boolean isPackage = check(IDENT) && "pkg".equals(peek().lexeme());
        if (isPackage) advance();
        String path = consume(STRING, "expected quoted import path").lexeme();
        if (path.isBlank()) throw error(previous(), "import path cannot be empty");
        if (isPackage) path = packageImportPath(path);
        consume(SEMICOLON, "expected ';' after import");
        return new Ast.ImportDecl(kind, names, wildcard, namespace, path);
    }

    private String consumeImportAlias(String message) {
        String alias = consume(IDENT, message).lexeme();
        if (ROOT_MODULE.equals(alias)) {
            throw error(previous(), "import alias '" + ROOT_MODULE + "' is reserved for file-root declarations");
        }
        return alias;
    }

    private void parseImportSelection(Ast.ImportKind kind, List<String> names) {
        Token.Type closing = null;
        if (match(LBRACE)) closing = RBRACE;
        else if (match(LPAREN)) closing = RPAREN;

        if (closing != null) {
            if (check(closing)) throw error(peek(), "import selection cannot be empty");
            do names.add(consumeImportName(kind)); while (match(COMMA));
            consume(closing, closing == RBRACE
                    ? "expected '}' after imported names"
                    : "expected ')' after imported names");
            return;
        }

        names.add(consumeImportName(kind));
        while (match(COMMA)) names.add(consumeImportName(kind));
    }

    private String consumeImportName(Ast.ImportKind kind) {
        if (kind == Ast.ImportKind.FUNCTION) {
            return consumeCallableName("expected imported function name");
        }
        String name = consume(IDENT, "expected imported name").lexeme();
        if (kind == Ast.ImportKind.MODULE && ROOT_MODULE.equals(name)) {
            throw error(previous(), "module name '" + ROOT_MODULE + "' is reserved for file-root declarations");
        }
        return name;
    }

    /**
     * Opaque module metadata is retained for source tooling, but compiler
     * directives bound to other targets cannot silently become no-ops.
     * This is deliberately placement-specific: callable and field annotations
     * are checked by their owning semantic passes.
     */
    private void rejectModuleCompilerAnnotations(List<Ast.Annotation> annotations) {
        java.util.Set<String> invalid = java.util.Set.of(
                "Ret", "FromJson", "AllowStructural", "structural",
                "ActorMaxThreads", "Critical", "CallerRole", "CallableBy",
                "ActorMiddleware", "NamedParams", "GrowthPolicy",
                "Allocator", "Align", "InlineCapacity", "MaxCapacity",
                "Storage", "Rank", "RequiresPermission", "Permission",
                "GrantPermission", "Unsafe", "Trusted", "Untrusted", "AllowAll");
        for (Ast.Annotation annotation : annotations) {
            if (invalid.contains(annotation.name())) {
                throw error(previous(), "@" + annotation.name()
                        + " is not a supported module annotation; compiler annotations cannot be inert metadata");
            }
        }
    }

    private Ast.ModuleDecl parseModule(List<Ast.Annotation> annotations) {
        rejectModuleCompilerAnnotations(annotations);
        String name = consume(IDENT, "expected flat module name").lexeme();
        if (ROOT_MODULE.equals(name)) {
            throw error(previous(), "module name '" + ROOT_MODULE + "' is reserved for file-root declarations");
        }
        if (name.equals(ROOT_MODULE)) {
            throw error(previous(), "module name '" + ROOT_MODULE + "' is reserved for file-root declarations");
        }
        if (check(DOT)) throw error(peek(), "modules cannot be nested or dotted");
        List<Ast.TypeRef> contracts = match(CONFORMS) ? parseTypeRefList() : List.of();
        // Canonical module syntax is "define module Name as ... end".
        // Keep the pre-"as" spelling accepted while existing source migrates.
        match(AS);
        List<Ast.Decl> declarations = new ArrayList<>();
        List<ForwardType> enclosing = moduleForwards;
        List<ForwardType> forwards = new ArrayList<>();
        moduleForwards = forwards;
        try {
            while (!check(END) && !check(EOF)) {
                Ast.Decl declaration = parseModuleMember();
                if (declaration != null) declarations.add(declaration);
            }
            consume(END, "expected 'end' to close module " + name);
            validateForwardTypes(forwards, declarations, "module " + name);
        } finally {
            moduleForwards = enclosing;
        }
        return new Ast.ModuleDecl(name, annotations, contracts, declarations);
    }

    private void rejectUnsupportedAcceleratorSyntax() {
        if (check(GPU) || check(QUANTUM)) {
            throw error(peek(), "gpu/quantum execution requires validated effect and ownership checking, "
                    + "device lowering and explicit backend admission; implicit CPU fallback is forbidden");
        }
    }

    private IllegalArgumentException unsupportedSingleton(Token keyword) {
        return error(keyword, "ORES-SINGLETON-UNSUPPORTED: define singleton requires "
                + "compiler ownership, initialization and guest execution parity; "
                + "the check-only AST does not authorize runtime lowering");
    }

    private Ast.SingletonDecl parseSingleton(
            Modifiers modifiers, List<Ast.Annotation> annotations, Token keyword) {
        validateOnlyVisibilityModifiers(modifiers, "singletons");
        rejectDiscardedDeclarationAnnotations(annotations, "singleton declarations");
        Token declaredName = consume(IDENT, "expected singleton name");
        if (check(DOT)) throw error(peek(), "singleton names must be flat");
        consume(AS, "singleton declaration requires 'as' before fields");
        List<Ast.SingletonField> fields = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        while (!check(END) && !check(EOF)) {
            Token position = peek();
            List<Ast.Annotation> fieldAnnotations = parseAnnotations();
            Modifiers fieldModifiers = parseModifiers();
            validateOnlyVisibilityModifiers(fieldModifiers, "singleton fields");
            if (!isBindingKind(peek().type())) {
                throw error(peek(), "ORES-SINGLETON-CHECKONLY: only explicit const/val/let "
                        + "field bindings are parsed; methods and runtime behavior are not enabled");
            }
            Ast.FieldDecl field = parseModuleBinding(fieldAnnotations, fieldModifiers.visibility);
            if (!names.add(field.name())) {
                throw error(position, "duplicate singleton field '" + field.name() + "'");
            }
            fields.add(new Ast.SingletonField(field, position.line(), position.column()));
            if (fields.size() > 64) {
                throw error(position, "ORES-SINGLETON-CHECKONLY: field budget exceeded");
            }
        }
        consume(END, "expected 'end' to close singleton " + declaredName.lexeme());
        return new Ast.SingletonDecl(declaredName.lexeme(), modifiers.visibility,
                fields, keyword.line(), keyword.column());
    }

    private Ast.Decl parseModuleMember() {
        rejectUnsupportedAcceleratorSyntax();
        List<Ast.Annotation> annotations = parseAnnotations();
        Modifiers modifiers = parseModifiers();

        // A module is a file-level container. Its direct children may declare
        // types, but may not introduce another module scope.
        if (check(MODULE)) {
            throw error(peek(), "nested modules are not permitted; declare modules at file scope");
        }

        if (isContextualIdentifier("declare")) {
            if (moduleForwards == null) throw error(peek(), "forward declaration outside module scope");
            moduleForwards.add(parseForwardType(annotations, modifiers));
            return null;
        }

        if (match(CLASS)) {
            modifiers = mergeModifiers(modifiers, parseModifiers());
            // 'define' is optional; retain the legacy class-first permutation.
            if (match(DEFINE)) modifiers = mergeModifiers(modifiers, parseModifiers());
            modifiers = mergeModifiers(modifiers, parseModifiers());
            rejectCallableStructuralAnnotation(annotations, "class declarations");
            validateCopyPolicyClassAnnotations(annotations);
            validateClassModifiers(modifiers);
            return parseClass(modifiers.visibility, modifiers.isAbstract, false, annotations);
        }

        if (check(CONTRACT) && defineFollowsAfterVisibilityModifiers()) {
            advance();
            modifiers = mergeModifiers(modifiers, parseModifiers());
            consume(DEFINE, "compatibility contract declaration requires both 'contract' and 'define'");
            modifiers = mergeModifiers(modifiers, parseModifiers());
            validateOnlyVisibilityModifiers(modifiers, "contracts");
            rejectCallableStructuralAnnotation(annotations, "contract declarations");
            rejectDiscardedDeclarationAnnotations(annotations, "contract declarations");
            return recordVisibility(parseContract(modifiers.visibility), modifiers);
        }

        if (check(INTERFACE) && defineFollowsAfterVisibilityModifiers()) {
            advance();
            modifiers = mergeModifiers(modifiers, parseModifiers());
            consume(DEFINE, "compatibility interface declaration requires both 'interface' and 'define'");
            modifiers = mergeModifiers(modifiers, parseModifiers());
            validateOnlyVisibilityModifiers(modifiers, "interfaces");
            rejectCallableStructuralAnnotation(annotations, "interface declarations");
            rejectDiscardedDeclarationAnnotations(annotations, "interface declarations");
            return parseDefinedInterface(modifiers.visibility, INTERFACE);
        }

        if (match(DEFINE)) {
            modifiers = mergeModifiers(modifiers, parseModifiers());
            if (check(MODULE)) {
                throw error(peek(), "nested modules are not permitted; declare modules at file scope");
            }
            if (isContextualIdentifier("singleton")) {
                Token keyword = advance();
                if (!singletonCheckOnly) throw unsupportedSingleton(keyword);
                return parseSingleton(modifiers, annotations, keyword);
            }
            if (isExternalActorMethodHead()) {
                return parseExternalActorMethod(annotations, modifiers);
            }
            if (isProtectedDeclarationHead()) {
                return parseProtectedDeclaration(modifiers, annotations);
            }
            rejectUnloweredRuntimeEntityDeclaration();
            ActorPrefix actorPrefix = parseActorPrefix();
            if (match(ACTOR, ISOACTOR)) {
                return parseDefinedActorClass(previous(), modifiers, annotations, actorPrefix);
            }
            if (modifiers.shared || modifiers.untrusted) {
                throw error(previous(), "'shared'/'untrusted' must modify an actor declaration");
            }
            if (match(CONTRACT)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                validateOnlyVisibilityModifiers(modifiers, "contracts");
                rejectCallableStructuralAnnotation(annotations, "contract declarations");
                rejectDiscardedDeclarationAnnotations(annotations, "contract declarations");
                return recordVisibility(parseContract(modifiers.visibility), modifiers);
            }
            if (match(CLASS)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                rejectCallableStructuralAnnotation(annotations, "class declarations");
                validateCopyPolicyClassAnnotations(annotations);
                validateClassModifiers(modifiers);
                return parseClass(modifiers.visibility, modifiers.isAbstract, false, annotations);
            }
            if (match(INTERFACE, TRAIT)) {
                Token.Type declarationKind = previous().type();
                modifiers = mergeModifiers(modifiers, parseModifiers());
                validateOnlyVisibilityModifiers(modifiers, "interfaces/traits");
                rejectCallableStructuralAnnotation(annotations, "interface declarations");
                rejectDiscardedDeclarationAnnotations(annotations, "interface declarations");
                return recordVisibility(parseDefinedInterface(modifiers.visibility, declarationKind), modifiers);
            }
            if (match(TYPE)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                validateOnlyVisibilityModifiers(modifiers, "type aliases");
                rejectCallableStructuralAnnotation(annotations, "type aliases");
                rejectDiscardedDeclarationAnnotations(annotations, "type aliases");
                return recordVisibility(parseTypeAlias(modifiers.visibility), modifiers);
            }
            if (hasDirectStructAttachment()) {
                validateOnlyVisibilityModifiers(modifiers, "attached struct declarations");
                rejectCallableStructuralAnnotation(annotations, "attached struct declarations");
                rejectDiscardedDeclarationAnnotations(annotations, "attached struct declarations");
                return parseDirectStructAttachment(modifiers.visibility);
            }
            // Legacy nested `define` is accepted, but never creates a new
            // scope or changes the callable's public symbol identity.
            if (check(FNC) || check(ROUTINE) || looksLikeReturnFirstCallableHead()) {
                return parseDeclarationAfterModifiers(annotations, modifiers);
            }
            rejectUnimplementedInvertedAggregate();
            throw error(previous(), "expected class, interface, trait, type, fnc, or routine after 'define'");
        }

        Ast.Decl declaration = parseDeclarationAfterModifiers(annotations, modifiers);
        if (declaration != null) return declaration;
        throw error(peek(), "expected function, routine, class, interface, trait, type, or binding declaration");
    }

    private Ast.Decl parseDeclarationAfterModifiers(List<Ast.Annotation> annotations, Modifiers modifiers) {
        rejectUnsupportedAcceleratorSyntax();
        // Allow 'pub mtd (Actor self) method(...)' without a redundant
        // 'define'. Bare 'mtd' stays contextual, not a new global keyword.
        if (modifiers.visibilitySeen && isExternalActorMethodHead()) {
            return parseExternalActorMethod(annotations, modifiers);
        }
        if (isLegacyFnSpelling()) {
            throw error(peek(), "functions are declared with 'fnc', not 'fn'");
        }
        if (match(ACTOR, ISOACTOR)) {
            Token actorToken = previous();
            boolean isolated = actorToken.type() == ISOACTOR;
            modifiers = mergeModifiers(modifiers, parseModifiers());

            if (isLegacyFnSpelling()) {
                throw error(peek(), "actor functions are declared with 'actor fnc', not 'actor fn'");
            }
            if (match(FNC)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                Ast.ActorKind actorKind = resolveActorKind(actorToken, isolated, modifiers);
                return parseFunction(annotations, modifiers, Ast.CallableKind.FNC, actorKind);
            }
            if (match(ROUTINE)) {
                modifiers = mergeModifiers(modifiers, parseModifiers());
                Ast.ActorKind actorKind = resolveActorKind(actorToken, isolated, modifiers);
                return parseFunction(annotations, modifiers, Ast.CallableKind.ROUTINE, actorKind);
            }

            Ast.ActorKind actorKind = resolveActorKind(actorToken, isolated, modifiers);
            if (modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural || modifiers.nonLexical || modifiers.trapped
                    || modifiers.isStatic || modifiers.isAbstract) {
                throw error(actorToken, "actor class declarations accept only visibility and actor-kind modifiers");
            }
            rejectCallableStructuralAnnotation(annotations, "actor class declarations");
            rejectDiscardedDeclarationAnnotations(annotations, "actor class declarations");
            return recordVisibility(parseActorClass(actorKind, modifiers.visibility), modifiers);
        }
        if (match(FNC)) {
            modifiers = mergeModifiers(modifiers, parseModifiers());
            if (match(ACTOR, ISOACTOR)) {
                Token actorToken = previous();
                boolean isolated = actorToken.type() == ISOACTOR;
                modifiers = mergeModifiers(modifiers, parseModifiers());
                Ast.ActorKind actorKind = resolveActorKind(actorToken, isolated, modifiers);
                return parseFunction(annotations, modifiers, Ast.CallableKind.FNC, actorKind);
            }
            if (modifiers.shared || modifiers.untrusted) {
                throw error(previous(), "'shared'/'untrusted' must modify an actor declaration");
            }
            return parseFunction(annotations, modifiers, Ast.CallableKind.FNC);
        }
        if (match(ROUTINE)) {
            modifiers = mergeModifiers(modifiers, parseModifiers());
            if (match(ACTOR, ISOACTOR)) {
                Token actorToken = previous();
                boolean isolated = actorToken.type() == ISOACTOR;
                modifiers = mergeModifiers(modifiers, parseModifiers());
                Ast.ActorKind actorKind = resolveActorKind(actorToken, isolated, modifiers);
                return parseFunction(annotations, modifiers, Ast.CallableKind.ROUTINE, actorKind);
            }
            if (modifiers.shared || modifiers.untrusted) {
                throw error(previous(), "'shared'/'untrusted' must modify an actor declaration");
            }
            return parseFunction(annotations, modifiers, Ast.CallableKind.ROUTINE);
        }
        // Return-type-first declarations are ordinary free/module fnc values,
        // not a new callable kind. The marker is checked by the lexer.
        if (looksLikeReturnFirstCallableHead()) {
            if (modifiers.shared || modifiers.untrusted || modifiers.isStatic) {
                throw error(peek(), "return-first free fnc cannot carry actor/static modifiers");
            }
            return parseFunction(annotations, modifiers, Ast.CallableKind.FNC);
        }
        if (modifiers.generator) {
            if (!modifiers.generatorStarred) {
                throw error(peek(),
                        "bare generator declarations require 'gen*' or 'generator*'; "
                                + "legacy 'generator' must modify fnc/routine");
            }
            if (modifiers.shared || modifiers.untrusted) {
                throw error(previous(), "'shared'/'untrusted' must modify an actor declaration");
            }
            return parseFunction(annotations, modifiers, Ast.CallableKind.FNC);
        }
        if (match(CONTRACT)) {
            validateOnlyVisibilityModifiers(modifiers, "contracts");
            rejectCallableStructuralAnnotation(annotations, "contract declarations");
            rejectDiscardedDeclarationAnnotations(annotations, "contract declarations");
            return recordVisibility(parseContract(modifiers.visibility), modifiers);
        }
        if (match(INTERFACE, TRAIT)) {
            Token.Type declarationKind = previous().type();
            validateOnlyVisibilityModifiers(modifiers, "interfaces/traits");
            rejectCallableStructuralAnnotation(annotations, "interface/trait declarations");
            rejectDiscardedDeclarationAnnotations(annotations, "interface/trait declarations");
            return recordVisibility(parseDefinedInterface(modifiers.visibility, declarationKind), modifiers);
        }
        if (match(TYPE)) {
            modifiers = mergeModifiers(modifiers, parseModifiers());
            validateOnlyVisibilityModifiers(modifiers, "type aliases");
            rejectCallableStructuralAnnotation(annotations, "type aliases");
            rejectDiscardedDeclarationAnnotations(annotations, "type aliases");
            return recordVisibility(parseTypeAlias(modifiers.visibility), modifiers);
        }
        if (isBindingKind(peek().type())) {
            validateOnlyVisibilityModifiers(modifiers, "module bindings");
            return parseModuleBinding(annotations, modifiers.visibility);
        }
        return null;
    }

    private Ast.ActorKind resolveActorKind(Token actorToken, boolean isolated, Modifiers modifiers) {
        if (isolated && (modifiers.shared || modifiers.untrusted)) {
            throw error(
                    actorToken,
                    "'shared/untrusted isoactor' is contradictory; use either shared actor, untrusted actor, or isoactor");
        }
        if (modifiers.shared && modifiers.untrusted) {
            throw error(actorToken, "an actor cannot be both shared and untrusted");
        }
        if (modifiers.untrusted) return Ast.ActorKind.UNTRUSTED;
        return isolated ? Ast.ActorKind.PRIVATE : Ast.ActorKind.SHARED;
    }

    private Ast.FunctionDecl parseFunction(List<Ast.Annotation> annotations, Modifiers modifiers, Ast.CallableKind kind) {
        return parseFunction(annotations, modifiers, kind, Ast.ActorKind.NONE);
    }

    private Ast.FunctionDecl parseFunction(
            List<Ast.Annotation> annotations,
            Modifiers modifiers,
            Ast.CallableKind kind,
            Ast.ActorKind actorKind) {
        if (modifiers.isStatic) throw error(previous(), "'static fnc' is only valid inside a class");
        if (modifiers.isAbstract) throw error(previous(), "top-level/module callables cannot be abstract");
        if (modifiers.trapped && (modifiers.async || modifiers.suspends)) {
            throw error(previous(), "suspending trap fnc/routine is not enabled until trap spans every await suspension");
        }
        if (modifiers.trapped && modifiers.generator) {
            throw error(previous(), "trap generator fnc/routine is not enabled until trap spans generator suspension");
        }
        if (modifiers.trapped && actorKind != Ast.ActorKind.NONE) {
            throw error(previous(), "trap actor callables are not enabled until actor supervision preserves the trap effect");
        }
        CallableStructural structural = normalizeCallableStructural(annotations, modifiers.structural);
        annotations = structural.annotations();
        Ast.TypeRef prefixReturnType = parseReturnFirstTypeIfPresent();
        String name = consumeCallableName("expected callable name");
        List<Ast.GenericParameter> specs = parseGenericParameterSpecs();
        List<String> generics = specs.stream().map(Ast.GenericParameter::name).toList();

        if (match(EQUAL)) {
            if (prefixReturnType != null) {
                throw error(previous(), "return-first declarations require a parameter list and callable body");
            }
            consume(PIPE, "lambda-style callable declarations use '= |Type name, ...| -> [ReturnType] { ... }'");
            List<Ast.Param> params = parseDeclaredPipeParameters();
            consume(PIPE, "expected closing '|' in lambda-style callable declaration");
            consume(ARROW, "lambda-style callable declarations use the slim arrow '->'");
            Ast.TypeRef returnType = check(LBRACE)
                    ? omittedNamedCallableReturn()
                    : parseTypeRef();
            Ast.EffectSpec effects = parseCheckedEffects();
            List<Ast.Stmt> body = parseBlock();
            return recordVisibility(new Ast.FunctionDecl(name, kind, modifiers.visibility, modifiers.async, modifiers.suspends, modifiers.generator, structural.enabled(), modifiers.nonLexical, modifiers.trapped, actorKind,
                    generics, params, returnType, annotations, body, specs, effects), modifiers);
        }

        consume(LPAREN, "expected '(' after callable name or '=' for lambda-style declaration");
        java.util.Set<String> structuralNames = structuralAnnotationNames(annotations);
        List<Ast.Param> params = applyStructuralAnnotations(parseParametersUntil(RPAREN, structuralNames), annotations);
        consume(RPAREN, "expected ')' after parameters");
        boolean arrowReturn = check(ARROW);
        Ast.TypeRef returnType = prefixReturnType == null
                ? parseReturnType(annotations)
                : validateReturnFirstType(prefixReturnType, annotations);
        Ast.EffectSpec effects = parseCheckedEffects();
        List<Ast.Stmt> body = prefixReturnType != null
                ? parseReturnFirstCallableBody(!effects.isEmpty())
                : parseFreeCallableBody(arrowReturn && actorKind == Ast.ActorKind.NONE);
        return recordVisibility(new Ast.FunctionDecl(name, kind, modifiers.visibility, modifiers.async, modifiers.suspends, modifiers.generator, structural.enabled(), modifiers.nonLexical, modifiers.trapped, actorKind, generics, params,
                returnType, annotations, body, specs, effects), modifiers);
    }

    /**
     * A type-first callable must have the complete form Type <- name(...)
     * or Type• name(...). Both markers lower to the existing typed callable
     * AST. Lookahead restores the cursor even on malformed generic types.
     */
    // Recognize <- only in declaration context. In expressions the same
    // unspaced characters mean '<' followed by unary '-', so the lexer
    // must continue emitting LT and MINUS as separate tokens.
    private boolean isAdjacentReturnFirstArrow() {
        return check(LT) && checkNext(MINUS)
                && adjacent(tokens.get(current), tokens.get(current + 1));
    }

    private boolean matchReturnFirstMarker() {
        if (match(BULLET)) return true;
        if (isAdjacentReturnFirstArrow()) {
            advance(); // <
            advance(); // -
            return true;
        }
        return false;
    }

    private boolean looksLikeReturnFirstCallableHead() {
        int mark = current;
        try {
            parseTypeRef();
            if (!matchReturnFirstMarker()) return false;
            consumeCallableName("expected callable name after return-first marker");
            parseGenericParameterSpecs();
            return check(LPAREN);
        } catch (IllegalArgumentException malformed) {
            return false;
        } finally {
            current = mark;
        }
    }

    private String peekReturnFirstCallableName() {
        if (!looksLikeReturnFirstCallableHead()) return null;
        int mark = current;
        try {
            parseTypeRef();
            // '<-' consists of two contextual tokens (LT and MINUS).
            // Advancing only once leaves the cursor on MINUS, allowing
            // return-first actor lifecycle names to bypass their CONTROL
            // admission gate. Consume the complete validated marker.
            if (!matchReturnFirstMarker()) {
                throw error(peek(), "malformed return-first callable marker");
            }
            return peek().lexeme();
        } finally {
            current = mark;
        }
    }

    private Ast.TypeRef parseReturnFirstTypeIfPresent() {
        if (!looksLikeReturnFirstCallableHead()) return null;
        Ast.TypeRef type = parseTypeRef();
        if (!matchReturnFirstMarker()) {
            throw error(peek(), "return-first callable requires '<-' or '•'");
        }
        return type;
    }

    private Ast.TypeRef validateReturnFirstType(Ast.TypeRef type, List<Ast.Annotation> annotations) {
        if (check(COLON) || check(ARROW) || check(FAT_ARROW)) {
            throw error(peek(), "return-first callable cannot declare a second return type");
        }
        boolean annotated = annotations.stream().anyMatch(a -> a.name().equals("Ret"));
        // The return-first prefix already supplies the mandatory return
        // annotation. Do not ask the ordinary suffix parser to infer void.
        Ast.TypeRef annotationType = annotated ? parseReturnType(annotations) : type;
        if (annotated && !sameType(type, annotationType)) {
            throw error(previous(), "@Ret type and return-first declared type disagree");
        }
        return type;
    }

    /**
     * Return-first headers preserve the ordinary callable body families.
     * Braces are a complete block; contextual begin/end and bare statements/end
     * are both explicit end-terminated bodies with ordinary (non-tail-return)
     * statement semantics. Neither marker, '<-' nor U+2022 BULLET, changes
     * lexical scope, effects or the callable's declared return type.
     */
    private List<Ast.Stmt> parseReturnFirstCallableBody(boolean inlineEffects) {
        if (check(LBRACE)) return parseBlock();
        // Retain optional begin; :: is the other exact delimiter after a
        // complete checked effect list. Both use explicit statement returns,
        // never a legacy comma-tail implicit result.
        boolean explicitBegin = matchContextualKeyword("begin");
        boolean explicitColonColon = !explicitBegin && match(DOUBLE_COLON);
        if (inlineEffects && !explicitBegin && !explicitColonColon) {
            throw error(peek(), "return-first effects require begin, :: or braces before the body");
        }
        if (explicitColonColon && (check(LBRACE) || !isImplicitNewlineTerminator())) {
            throw error(peek(), "return-first :: requires statements/end on a later physical line");
        }
        localTypeScopes.push(new java.util.HashSet<>());
        try {
            List<Ast.Stmt> body = parseUntil(END);
            consume(END, "expected 'end' after return-first callable body");
            match(DOT, SEMICOLON);
            return body;
        } finally {
            localTypeScopes.pop();
        }
    }

    /** Contextual mtd accepts both receiver-first and name-first declarations. */
    private boolean isExternalActorMethodHead() {
        return isContextualIdentifier("mtd")
                && current + 1 < tokens.size()
                && (tokens.get(current + 1).type() == LPAREN
                    || (current + 2 < tokens.size()
                        && tokens.get(current + 1).type() == IDENT
                        && tokens.get(current + 2).type() == LPAREN));
    }

    private Ast.ExternalActorMethodDecl parseExternalActorMethod(
            List<Ast.Annotation> annotations, Modifiers modifiers) {
        validateOnlyVisibilityModifiers(modifiers, "external actor receiver methods");
        rejectCallableStructuralAnnotation(annotations, "external actor receiver methods");
        rejectDiscardedDeclarationAnnotations(annotations, "external actor receiver methods");
        consumeContextualIdentifier("mtd", "expected external receiver method declaration");
        // The canonical form is 'define mtd (Worker w) update(...): void'.
        // Keep the earlier 'define mtd update(Worker w)(...): void' as
        // a compatibility spelling; both produce one identical typed AST.
        boolean receiverFirst = check(LPAREN);
        String name = receiverFirst ? null
                : consumeCallableName("expected external actor method name");
        consume(LPAREN, "external receiver method requires '(Actor receiver)'");
        Ast.TypeRef receiverType = parseTypeRef();
        if (receiverType.inferArguments() || !receiverType.arguments().isEmpty()
                || receiverType.isBorrow()) {
            throw error(previous(), "external receiver must be a non-generic nominal actor");
        }
        String receiverName = consume(IDENT, "expected external receiver binding name").lexeme();
        consume(RPAREN, "expected ')' after external actor receiver");
        if (receiverFirst) {
            name = consumeCallableName("expected external actor method name after receiver");
        }
        if (java.util.Set.of("handle_state", "on_panic", "on_throw", "on_raise",
                "on_recover").contains(name)) {
            throw error(previous(), "external actor receiver cannot declare compiler-owned or CONTROL hooks");
        }
        consume(LPAREN, "expected '(' before external receiver method parameters");
        List<Ast.Param> parameters = parseParametersUntil(RPAREN);
        consume(RPAREN, "expected ')' after external receiver method parameters");
        if (parameters.stream().anyMatch(p -> p.structural()
                || p.name().equals(receiverName))) {
            throw error(previous(), "external receiver methods cannot use structural parameters "
                    + "or shadow the receiver binding");
        }
        Ast.TypeRef returns = match(FAT_ARROW, COLON)
                ? parseTypeRef() : omittedNamedCallableReturn();
        if (!parseCheckedEffects().isEmpty()) {
            throw error(previous(), "external receiver effects require checked actor-effect lowering");
        }
        List<Ast.Stmt> body = parseBlock();
        return new Ast.ExternalActorMethodDecl(
                receiverType.name(), receiverName, name,
                modifiers.visibility, parameters, returns, body);
    }

    /** Free/module functions may use '-> Type' then newline/end or '::' body/end. */
    private List<Ast.Stmt> parseFreeCallableBody(boolean arrowReturn) {
        if (check(LBRACE) || !arrowReturn) return parseBlock();
        boolean explicit = match(DOUBLE_COLON);
        if (attoCallableVersion != null &&
                (explicit || attoCallableVersion == AttoCallableVersion.V1))
            return parseVersionedCommaTailBody();
        if (!explicit && attoCallableVersion == null && !isImplicitNewlineTerminator()) {
            throw error(peek(), "end-delimited callable needs a newline or '::' after its return type");
        }
        localTypeScopes.push(new java.util.HashSet<>());
        try {
            List<Ast.Stmt> body = parseUntil(END);
            consume(END, "expected 'end' after callable body");
            return body;
        } finally {
            localTypeScopes.pop();
        }
    }

    /** Restricted, executable pure expression sequence. The enclosing
     * checker/runtime still checks calls and types; declarations, statement
     * effects and nested control-flow are deliberately not flattened.
     * Commas inside call arguments are consumed by parseExpression and cannot
     * masquerade as body separators. */
    private List<Ast.Stmt> parseVersionedCommaTailBody() {
        localTypeScopes.push(new java.util.HashSet<>());
        try {
            List<Ast.Stmt> body = new ArrayList<>();
            for (int count = 0; count < 1024; ++count) {
                if (check(END) || check(EOF) || check(COMMA))
                    throw error(peek(), "E_SYNTAX: missing comma-body expression");
                if (check(LET) || check(VAL) || check(CONST)
                        || check(RETURN) || check(CARET))
                    throw error(peek(), "E_UNSUPPORTED: versioned comma body currently requires checked expression items");
                Ast.Expr value = parseExpression();
                if (match(COMMA)) {
                    if (check(END) || check(EOF) || check(COMMA))
                        throw error(peek(), "E_SYNTAX: empty/trailing comma-body item");
                    body.add(new Ast.ExprStmt(value));
                    continue;
                }
                if (check(SEMICOLON))
                    throw error(peek(), "E_SYNTAX: comma body uses ',' not ';'");
                body.add(new Ast.ReturnStmt(value));
                consume(END, "expected 'end' after versioned comma body");
                return List.copyOf(body);
            }
            throw error(peek(), "E_SOURCE_LIMIT: comma body exceeds 1024 items");
        } finally {
            localTypeScopes.pop();
        }
    }

    private Ast.ClassDecl parseClass(
            Ast.Visibility visibility,
            boolean isAbstract,
            boolean filePrivateScope,
            List<Ast.Annotation> classAnnotations) {
        String name = consumeTypeDeclarationName("expected class name");
        List<String> generics = parseGenericParameters();
        List<Ast.TypeRef> parents = match(EXTENDS) ? parseTypeRefList() : List.of();
        List<Ast.TypeRef> interfaces = match(IMPLEMENTS, IMPL) ? parseTypeRefList() : List.of();
        consume(AS, "expected 'as' after class header");
        // Also accept 'class Name as implements Trait<T> as ... end'.
        // The first 'as' always opens the class declaration header.
        if (match(IMPLEMENTS, IMPL)) {
            if (!interfaces.isEmpty()) {
                throw error(previous(), "class cannot declare 'implements' both before and after 'as'");
            }
            interfaces = parseTypeRefList();
            consume(AS, "expected 'as' after class implements clause");
        }
        boolean braceStyle = match(LBRACE);
        Token.Type classTerminator = braceStyle ? RBRACE : END;
        List<Ast.FieldDecl> fields = new ArrayList<>();
        List<Ast.MethodDecl> methods = new ArrayList<>();
        Ast.ConstructorDecl constructor = null;

        while (!check(classTerminator) && !check(EOF)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers mods = parseModifiers();

            // Nested members never require `define`, but admit either legacy
            // modifier ordering so formatting is not a prerequisite to parsing.
            if (match(DEFINE)) {
                mods = mergeModifiers(mods, parseModifiers());
            }
            if (check(DEFINE) || check(CLASS) || check(MODULE) || check(INTERFACE)
                    || check(TRAIT) || check(TYPE) || check(ACTOR) || check(ISOACTOR)) {
                throw error(peek(),
                        "declarations cannot be nested inside classes; declare nested types at file or module scope");
            }

            if (isContextualIdentifier("constructor")) {
                if (constructor != null) {
                    throw error(peek(), "class '" + name + "' may declare only one constructor");
                }
                constructor = parseConstructor(
                        annotations, mods, name, visibility, filePrivateScope);
                continue;
            }

            if (check(IDENT) && peek().lexeme().equals(name) && checkNext(LPAREN)) {
                throw error(peek(),
                        "constructors are declared with 'constructor(...)', not the class name '"
                                + name + "(...)'");
            }

            if (isLegacyFnSpelling()) {
                if (mods.isStatic) throw error(peek(), "static class functions use 'static fnc', not 'static fn'");
                throw error(peek(), "instance methods omit 'fn'/'fnc'; declare the method name directly");
            }
            if (isBindingKind(peek().type())) {
                validateClassFieldModifiers(mods);
                Ast.FieldDecl field = parseField(annotations, mods.visibility);
                if (field.bindingKind() == Ast.BindingKind.CONST
                        && field.initializer() == null) {
                    throw error(previous(), "final/const class field '" + field.name()
                            + "' requires a declaration-site initializer");
                }
                fields.add(field);
                continue;
            }
            if (check(IDENT) && checkNext(COLON)) {
                validateClassFieldModifiers(mods);
                fields.add(parseColonField(annotations, mods.visibility));
                continue;
            }
            // Type-first fields accept ':' canonically and '=' for compatibility.
            // 'final' is contextual, so final(...) remains an ordinary method.
            if ((isContextualIdentifier("final") && (checkNext(IDENT) || checkNext(MUT)))
                    || looksLikeTypeFirstClassField()) {
                validateClassFieldModifiers(mods);
                fields.add(parseTypeFirstClassField(annotations, mods.visibility));
                continue;
            }

            boolean explicitFnc = match(FNC);
            boolean explicitMethod = matchExplicitMethodMarker();
            if (explicitFnc && explicitMethod) {
                throw error(previous(), "an instance member cannot combine 'fnc' and 'method' markers");
            }
            if (explicitFnc || explicitMethod) {
                mods = mergeModifiers(mods, parseModifiers());
            }
            if (mods.generator) throw error(peek(), "'generator' is not permitted on class methods or static class fnc; declare a module/top-level generator fnc or routine");
            if (mods.nonLexical) throw error(peek(), "'nlex' is unnecessary on class members; methods/static fnc never capture enclosing local scopes");
            if (mods.trapped) throw error(peek(), "'trap' on class methods/static fnc is not enabled until method callable metadata carries the trap effect");
            if (mods.shared || mods.untrusted) throw error(peek(), "actor-kind modifiers are not permitted on class members");
            if (mods.isStatic) {
                if (explicitMethod) {
                    throw error(previous(), "static class functions use 'static fnc', not 'static method'");
                }
                if (!explicitFnc) {
                    consume(FNC, "static class functions must be declared with 'static fnc'");
                }
                if (mods.isAbstract) throw error(previous(), "static class functions cannot be abstract");
            }
            // Legacy 'fnc name' and explicit 'method name' both describe instance
            // methods. The AST's isStatic flag, not the spelling, controls binding.
            Ast.MethodDecl member = parseMethod(annotations, mods, isAbstract);
            // Newable's explicitly declared `fnc new` is a type-level
            // factory even when the author omits the optional `static`
            // modifier. Do not alter other methods or synthesize a factory.
            if (member.name().equals("new") && !member.isStatic()
                    && interfaces.stream().anyMatch(ref -> ref.name().equals("Newable"))) {
                if (member.explicitReceiverType() != null) {
                    throw error(previous(), "Newable.new cannot declare a self receiver");
                }
                member = new Ast.MethodDecl(member.name(), member.visibility(),
                        true, member.isAbstract(), member.async(), member.suspends(),
                        member.structural(), member.explicitReceiverType(),
                        member.genericParameters(), member.parameters(), member.returnType(),
                        member.annotations(), member.body(), member.effects());
            }
            methods.add(member);
        }
        consume(
                classTerminator,
                braceStyle
                        ? "expected '}' to close class " + name
                        : "expected 'end' to close class " + name);
        return new Ast.ClassDecl(
                name, visibility, isAbstract, Ast.ActorKind.NONE, generics,
                parents, interfaces, fields, constructor, methods,
                Ast.ActorConcurrency.serial(Ast.ActorTransport.GENERIC), List.of(), classAnnotations);
    }

    private Ast.ConstructorDecl parseConstructor(
            List<Ast.Annotation> annotations,
            Modifiers mods,
            String className,
            Ast.Visibility classVisibility,
            boolean filePrivateScope) {
        rejectNonCallableReturnAnnotation(annotations, "constructors");
        Token keyword = advance(); // contextual 'constructor'
        if (mods.async || mods.suspends || mods.generator || mods.structural || mods.nonLexical || mods.trapped
                || mods.isStatic || mods.isAbstract || mods.shared || mods.untrusted) {
            throw error(keyword,
                    "constructors accept only private/pub visibility; callable/actor modifiers are not permitted");
        }
        if (mods.visibility == Ast.Visibility.PUBLIC
                && (filePrivateScope || classVisibility != Ast.Visibility.PUBLIC)) {
            throw error(keyword,
                    "a public constructor requires a public class; private file-root or module classes cannot export constructors");
        }

        consume(LPAREN, "expected '(' after constructor");
        List<Ast.Param> params = parseParametersUntil(RPAREN);
        consume(RPAREN, "expected ')' after constructor parameters");
        if (check(COLON) || check(ARROW) || check(FAT_ARROW)) {
            throw error(peek(), "constructors do not declare a return type");
        }
        List<Ast.Stmt> body = parseBlock();
        return new Ast.ConstructorDecl(mods.visibility, params, annotations, body);
    }

    private boolean defineFollowsAfterVisibilityModifiers() {
        int lookahead = current + 1;
        while (lookahead < tokens.size()) {
            Token.Type type = tokens.get(lookahead).type();
            if (type == PUB || type == PRIVATE) {
                lookahead++;
                continue;
            }
            return type == DEFINE;
        }
        return false;
    }

    /**
     * `inverted` is a contextual keyword: it only means something directly after
     * `define` (and visibility/abstract modifiers), so `inverted` stays a legal
     * identifier elsewhere. `define inverted class|struct` is specified by
     * docs/INVERTED_CLASS_DOP_V1.md but has no lowering yet, so it fails closed
     * with a precise diagnostic instead of the generic `define` error or a silent
     * fallback to ordinary row-wise (AoS) class storage.
     */
    private void rejectUnimplementedInvertedAggregate() {
        if (!check(IDENT) || !peek().lexeme().equals("inverted")) return;
        Token keyword = peek();
        String kind = checkNext(CLASS) ? "class" : checkNext(STRUCT) ? "struct" : null;
        if (kind == null) {
            throw error(keyword, "'inverted' must be followed by 'class' or 'struct'");
        }
        throw error(keyword, "'define inverted " + kind + "' is specified (INVERTED_CLASS_DOP_V1) but not yet"
                + " executable on this backend; it is rejected rather than silently lowered to row-wise"
                + " (AoS) storage. Until lowering lands, keep one explicit array per field");
    }

    private void validateClassModifiers(Modifiers modifiers) {
        if (modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural || modifiers.nonLexical || modifiers.trapped
                || modifiers.isStatic || modifiers.shared || modifiers.untrusted) {
            throw error(previous(),
                    "classes accept only private/pub visibility and abstract; callable/actor modifiers are not permitted");
        }
    }

    private void validateClassFieldModifiers(Modifiers modifiers) {
        if (modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural || modifiers.nonLexical || modifiers.trapped
                || modifiers.isStatic || modifiers.isAbstract || modifiers.shared || modifiers.untrusted) {
            throw error(peek(), "class fields accept only private/pub visibility modifiers");
        }
    }

    private void validateOnlyVisibilityModifiers(Modifiers modifiers, String context) {
        if (modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural || modifiers.nonLexical
                || modifiers.trapped || modifiers.isStatic || modifiers.isAbstract || modifiers.shared || modifiers.untrusted) {
            throw error(previous(), context + " accept only private/pub visibility modifiers");
        }
    }

    private void validateInterfaceCallableModifiers(Modifiers modifiers) {
        if (modifiers.visibilitySeen || modifiers.async || modifiers.suspends || modifiers.generator || modifiers.nonLexical || modifiers.trapped
                || modifiers.isStatic || modifiers.isAbstract || modifiers.shared || modifiers.untrusted) {
            throw error(previous(),
                    "interface callable signatures currently accept only the structural callable modifier");
        }
    }

    private void validateNoModifiers(Modifiers modifiers, String context) {
        if (modifiers.visibilitySeen || modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural
                || modifiers.nonLexical || modifiers.trapped || modifiers.isStatic || modifiers.isAbstract
                || modifiers.shared || modifiers.untrusted) {
            throw error(previous(), context + " do not accept declaration modifiers");
        }
    }

    private boolean isContextualIdentifier(String lexeme) {
        return check(IDENT) && peek().lexeme().equals(lexeme);
    }

    /**
     * "define actor Name as ... end" is a declaration spelling, not a new actor
     * allocation primitive. Actor construction/spawn must still be lowered by
     * the runtime before source programs may instantiate actor classes.
     */
    private record ActorPrefix(Ast.ActorTransport transport, boolean serial) {
        private static final ActorPrefix DEFAULT =
                new ActorPrefix(Ast.ActorTransport.GENERIC, false);
    }

    /**
     * Contextual actor-only modifiers preserve ordinary variables named
     * "serial", "http", "tcp", "websocket" in unrelated grammar positions.
     * Recognizes both "define http actor" and "define serial http actor".
     */
    private ActorPrefix parseActorPrefix() {
        boolean serial = false;
        Ast.ActorTransport transport = Ast.ActorTransport.GENERIC;
        boolean recognized = false;
        for (;;) {
            if (matchContextualIdentifier("serial")) {
                if (serial) throw error(previous(), "duplicate serial actor modifier");
                serial = true;
                recognized = true;
            } else if (matchContextualIdentifier("http")) {
                if (transport != Ast.ActorTransport.GENERIC)
                    throw error(previous(), "actor cannot have multiple transport kinds");
                transport = Ast.ActorTransport.HTTP;
                recognized = true;
            } else if (matchContextualIdentifier("tcp")) {
                if (transport != Ast.ActorTransport.GENERIC)
                    throw error(previous(), "actor cannot have multiple transport kinds");
                transport = Ast.ActorTransport.TCP;
                recognized = true;
            } else if (matchContextualIdentifier("websocket")
                    || matchContextualIdentifier("ws")) {
                if (transport != Ast.ActorTransport.GENERIC)
                    throw error(previous(), "actor cannot have multiple transport kinds");
                transport = Ast.ActorTransport.WEBSOCKET;
                recognized = true;
            } else break;
        }
        if (recognized && !check(ACTOR) && !check(ISOACTOR))
            throw error(peek(), "serial/http/tcp/websocket modifiers require an actor declaration");
        return new ActorPrefix(transport, serial);
    }

    private Ast.Decl parseDefinedActorClass(
            Token actorToken,
            Modifiers modifiers,
            List<Ast.Annotation> annotations,
            ActorPrefix prefix) {
        modifiers = mergeModifiers(modifiers, parseModifiers());
        Ast.ActorKind kind = resolveActorKind(
                actorToken, actorToken.type() == ISOACTOR, modifiers);
        if (modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural
                || modifiers.nonLexical || modifiers.trapped || modifiers.isStatic
                || modifiers.isAbstract) {
            throw error(actorToken,
                    "actor class declarations accept only visibility and actor-kind modifiers");
        }
        rejectCallableStructuralAnnotation(annotations, "actor class declarations");
        rejectDiscardedDeclarationAnnotations(annotations, "actor class declarations");
        return recordVisibility(parseActorClass(kind, modifiers.visibility, prefix), modifiers);
    }

    /** Preserve first-class syntax; executable lowering stays type-gated. */
    private boolean isProtectedDeclarationHead() {
        if (current + 1 >= tokens.size()) return false;
        return (isContextualIdentifier("state")
                    && tokens.get(current + 1).lexeme().equals("container"))
                || (isContextualIdentifier("virtual")
                    && tokens.get(current + 1).type() == ACTOR)
                || (check(GPU) && tokens.get(current + 1).type() == ACTOR);
    }

    private Ast.Decl parseProtectedDeclaration(
            Modifiers modifiers, List<Ast.Annotation> annotations) {
        validateOnlyVisibilityModifiers(modifiers, "protected declarations");
        rejectCallableStructuralAnnotation(annotations, "protected declarations");
        rejectDiscardedDeclarationAnnotations(annotations, "protected declarations");
        boolean gpuActor = check(GPU);
        boolean virtualActor = !gpuActor && isContextualIdentifier("virtual");
        if (gpuActor) {
            consume(GPU, "expected gpu");
            consume(ACTOR, "expected actor after gpu");
        } else if (virtualActor) {
            consumeContextualIdentifier("virtual", "expected virtual");
            consume(ACTOR, "expected actor after virtual");
        } else {
            consumeContextualIdentifier("state", "expected state");
            consumeContextualIdentifier("container", "expected container after state");
        }
        String name = consume(IDENT, "expected protected declaration name").lexeme();
        consume(AS, "protected declarations require 'as'");
        consumeContextualIdentifier("state", "expected sealed state block");
        consume(LBRACE, "expected '{' after state");
        List<Ast.ProtectedStateField> fields = new ArrayList<>();
        java.util.HashSet<String> fieldNames = new java.util.HashSet<>();
        while (!check(RBRACE) && !check(EOF)) {
            consume(LET, "sealed state fields must begin with 'let'");
            Ast.TypeRef type = parseTypeRef();
            String field = consume(IDENT, "expected sealed state field name").lexeme();
            consume(COLON, "sealed state field requires ':' initializer");
            Ast.Expr initial = parseExpression();
            consume(SEMICOLON, "sealed state field requires ';'");
            if (!fieldNames.add(field))
                throw error(previous(), "duplicate protected state field '" + field + "'");
            fields.add(new Ast.ProtectedStateField(field, type, initial));
            if (fields.size() > 128)
                throw error(previous(), "protected state field budget exceeded (128)");
        }
        consume(RBRACE, "expected '}' after sealed state");
        List<Ast.ProtectedMethod> methods = new ArrayList<>();
        java.util.HashSet<String> methodsSeen = new java.util.HashSet<>();
        while (!check(END) && !check(EOF)) {
            String method = consume(IDENT, "expected protected method name").lexeme();
            consume(LPAREN, "expected '(' after protected method name");
            List<Ast.Param> parameters = new ArrayList<>();
            java.util.HashSet<String> parameterNames = new java.util.HashSet<>();
            if (!check(RPAREN)) {
                do {
                    Ast.TypeRef type = parseTypeRef();
                    String parameter = consume(IDENT, "expected protected method parameter").lexeme();
                    if (!parameterNames.add(parameter))
                        throw error(previous(), "duplicate protected parameter '" + parameter + "'");
                    parameters.add(new Ast.Param(type, parameter));
                } while (match(COMMA));
            }
            consume(RPAREN, "expected ')' after protected method parameters");
            consume(ARROW, "protected method return type requires '->'");
            Ast.TypeRef result = parseTypeRef();
            consume(DOUBLE_COLON, "protected method body requires '::'");
            List<String> body = new ArrayList<>();
            List<String> bodyKinds = new ArrayList<>();
            int braceDepth = 0;
            while (!check(EOF)) {
                if (check(END) && braceDepth == 0) break;
                if (check(DEFINE) || check(MODULE) || check(CLASS))
                    throw error(peek(), "nested declarations are forbidden in protected methods");
                Token t = advance();
                if (t.type() == LBRACE) braceDepth++;
                if (t.type() == RBRACE && --braceDepth < 0)
                    throw error(t, "unbalanced protected method body braces");
                body.add(t.lexeme());
                bodyKinds.add(t.type().name());
                if (body.size() > 1024)
                    throw error(t, "protected method token budget exceeded (1024)");
            }
            if (braceDepth != 0 || body.isEmpty())
                throw error(peek(), "unterminated or empty protected method body");
            consume(END, "expected 'end' after protected method");
            String methodIdentity = method + "/" + parameters.size();
            if (!methodsSeen.add(methodIdentity))
                throw error(previous(), "duplicate protected method name/arity '" + methodIdentity + "'");
            methods.add(new Ast.ProtectedMethod(method, parameters, result, body, bodyKinds));
            if (methods.size() > 64)
                throw error(previous(), "protected method budget exceeded (64)");
        }
        consume(END, "expected 'end' after protected declaration");
        if (gpuActor)
            return new Ast.GpuActorDecl(name, modifiers.visibility, fields, methods);
        if (virtualActor)
            return new Ast.VirtualActorDecl(name, modifiers.visibility, fields, methods);
        return new Ast.StateContainerDecl(name, modifiers.visibility, fields, methods);
    }

    /**
     * Do not treat supervision, proxying, scheduling or mail routing as a
     * class/actor alias before compiler-enforced CONTROL, isolation and
     * versioned hot-code replacement exist in both backends.
     */
    private void rejectUnloweredRuntimeEntityDeclaration() {
        if (check(EOF)) return;
        String name = peek().lexeme();
        if (name.equals("singleton")) {
            throw error(peek(), "ORES-SINGLETON-UNSUPPORTED: singleton declarations require "
                    + "exactly-once complete publication, cycle/failure semantics, "
                    + "runtime-derived actor authority for shared state, and "
                    + "five-backend guest execution parity; not yet enabled");
        }
        // Reserve the two-word declaration heads without mislowering them as
        // ordinary actor/class declarations. These are contextual identifiers,
        // so 'state' and 'virtual' remain valid names outside declaration heads.
        if (current + 1 < tokens.size()) {
            Token next = tokens.get(current + 1);
            if (name.equals("state") && next.lexeme().equals("container")) {
                throw error(peek(), "source state container declarations require "
                        + "compiler-checked protected-heap ownership, critical access "
                        + "and cross-backend lowering; not yet enabled");
            }
            if (name.equals("virtual") && next.type() == ACTOR) {
                throw error(peek(), "source virtual actor declarations require "
                        + "compiler-checked stable logical identity, activation fencing "
                        + "and cross-backend lowering; not yet enabled");
            }
        }
        // Do not silently lower the requested syntax to legacy isoactor,
        // which does not certify a physical actor-owned heap or sealed state.
        if (name.equals("isolated") && current + 1 < tokens.size()
                && tokens.get(current + 1).type() == ACTOR) {
            throw error(peek(), "define isolated actor requires compiler-owned "
                    + "arena isolation and sealed state-loan lowering; not yet enabled");
        }
        if (name.equals("readable") || name.equals("transform")
                || name.equals("writable") || name.equals("duplex")) {
            if (current + 2 < tokens.size()
                    && tokens.get(current + 1).lexeme().equals("pipe")
                    && tokens.get(current + 2).type() == ACTOR) {
                throw error(peek(), "source " + name + " pipe actor declarations "
                        + "require compiler-checked pipe role typing, bounded "
                        + "edge transport and cross-backend lowering; not yet enabled");
            }
            return;
        }
        // These are proposed *nominal* source kinds, not synonyms for class/actor.
        // Keep the spellings contextual so unrelated identifiers remain valid.
        // ('state container' is a first-class protected declaration on main and
        // is parsed by parseProtectedDeclaration, which requires a sealed state block.)
        if (name.equals("grain")) {
            throw error(peek(), "source grain declarations require stable keyed identity, "
                    + "activation/passivation fencing and actor isolation; not yet enabled");
        }
        if (name.equals("model")) {
            throw error(peek(), "source model declarations require typed ModelGraph "
                    + "admission and verified code generation; not yet enabled");
        }
        if (name.equals("enum")) {
            throw error(peek(), "source enum declarations require nominal ADT case "
                    + "typing, layout and match lowering; not yet enabled");
        }
        if (!name.equals("supervisor") && !name.equals("mailman")
                && !name.equals("scheduler") && !name.equals("proxy")
                && !name.equals("pipe")) return;
        throw error(peek(), "source " + name + " declarations require "
                + "compiler-checked runtime entity lowering, supervisor "
                + "CONTROL admission and cross-backend parity; not yet enabled");
    }

    private boolean matchContextualIdentifier(String lexeme) {
        if (!isContextualIdentifier(lexeme)) return false;
        advance();
        return true;
    }

    private void consumeContextualIdentifier(String lexeme, String message) {
        if (!matchContextualIdentifier(lexeme)) throw error(peek(), message);
    }

    private boolean matchCaseKeyword() {
        // CASE is retained for token-stream/backward compatibility, but the
        // lexer emits ordinary IDENT for "case" so it remains legal elsewhere.
        return match(CASE) || matchContextualIdentifier("case");
    }

    private boolean matchWhenKeyword() {
        // WHEN is retained for token-stream/backward compatibility, but the
        // lexer emits ordinary IDENT for "when" so it remains legal elsewhere.
        return match(WHEN) || matchContextualIdentifier("when");
    }

    private Ast.Decl parseActorClass(Ast.ActorKind actorKind, Ast.Visibility visibility) {
        return parseActorClass(actorKind, visibility, ActorPrefix.DEFAULT);
    }

    private Ast.Decl parseActorClass(
            Ast.ActorKind actorKind, Ast.Visibility visibility, ActorPrefix prefix) {
        String name = consumeTypeDeclarationName("expected actor name");
        List<String> generics = parseGenericParameters();
        // 'receives' and 'sends' are contextual actor-header keywords. Do
        // not steal existing method, local or field identifiers in unrelated
        // syntax; declaration order is arbitrary but duplicates are errors.
        Ast.TypeRef receives = null;
        Ast.TypeRef sends = null;
        while (isContextualIdentifier("receives") || isContextualIdentifier("sends")) {
            Token clause = advance();
            Ast.TypeRef declared = parseTypeRef();
            if (clause.lexeme().equals("receives")) {
                if (receives != null) throw error(clause, "duplicate actor receives clause");
                receives = declared;
            } else {
                if (sends != null) throw error(clause, "duplicate actor sends clause");
                sends = declared;
            }
        }
        List<Ast.TypeRef> parents = match(EXTENDS) ? parseTypeRefList() : List.of();
        // Actors have one runtime-owned mailbox, one lifecycle and one state
        // ownership domain. Multiple actor superclasses would merge those
        // domains without an admission or layout proof; reject at parse time
        // rather than letting ordinary-class diamond dispatch choose a parent.
        if (parents.size() > 1) {
            throw error(peek(), "actor '" + name
                    + "' may extend at most one actor superclass");
        }
        List<Ast.TypeRef> interfaces = match(IMPLEMENTS, IMPL) ? parseTypeRefList() : List.of();

        // A nominal, semicolon-terminated actor may name contract obligations
        // without defining an implicit runtime actor, empty class or alias.
        // Actual actor-contract conformance remains a separate checked gate.
        if (check(SEMICOLON)) {
            if (!generics.isEmpty() || !parents.isEmpty()
                    || actorKind != Ast.ActorKind.SHARED
                    || prefix.transport() != Ast.ActorTransport.GENERIC || prefix.serial()) {
                throw error(peek(), "semicolon actor declarations require a plain "
                        + "non-generic actor without transport or inheritance modifiers");
            }
            advance();
            return new Ast.StandaloneActorDecl(
                    name, null, visibility, actorKind, interfaces);
        }

        // Standalone actor declaration: retain the receiver-binding name in
        // its own AST node. Never synthesize an empty class, dispatch table,
        // or executable actor when receiver lowering has not been certified.
        if (check(AS) && current + 2 < tokens.size()
                && tokens.get(current + 1).type() == IDENT
                && tokens.get(current + 2).type() == SEMICOLON) {
            if (!generics.isEmpty() || !parents.isEmpty() || !interfaces.isEmpty()
                    || actorKind != Ast.ActorKind.SHARED
                    || prefix.transport() != Ast.ActorTransport.GENERIC || prefix.serial()) {
                throw error(peek(), "standalone actor declarations currently require a plain "
                        + "non-generic actor without transport or inheritance modifiers");
            }
            advance(); // as
            String receiverName = consume(IDENT, "expected standalone actor receiver binding").lexeme();
            consume(SEMICOLON, "standalone actor declaration requires ';'");
            return new Ast.StandaloneActorDecl(name, receiverName, visibility, actorKind);
        }

        boolean braceStyle = match(LBRACE);
        if (!braceStyle) match(AS);
        Token.Type terminator = braceStyle ? RBRACE : END;
        List<Ast.FieldDecl> fields = new ArrayList<>();
        List<Ast.MethodDecl> methods = new ArrayList<>();
        // Sealed actor state is typed, private AST metadata: NEVER an
        // ordinary Actor/Class field, mutable Java object or user-visible
        // state-loan capability. TypeChecker must reject until runtime
        // allocation and critical loan lowering are proven.
        List<Ast.ProtectedStateField> sealedStateFields = new ArrayList<>();
        boolean declaredSealedState = false;
        int requestConcurrency = 1;
        int messageConcurrency = 1;
        boolean declaredRequestConcurrency = false;
        boolean declaredMessageConcurrency = false;

        while (!check(terminator) && !check(EOF)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers mods = parseModifiers();
            if (match(DEFINE)) {
                mods = mergeModifiers(mods, parseModifiers());
            }
            if (isContextualIdentifier("state") && checkNext(LBRACE)) {
                if (declaredSealedState)
                    throw error(peek(), "duplicate sealed actor state container");
                if (!annotations.isEmpty() || mods.visibilitySeen || mods.async
                        || mods.suspends || mods.generator || mods.isStatic || mods.isAbstract
                        || mods.shared || mods.untrusted || mods.structural
                        || mods.nonLexical || mods.trapped)
                    throw error(peek(), "sealed actor state cannot have modifiers or annotations");
                declaredSealedState = true;
                advance(); // contextual 'state', not an actor field
                consume(LBRACE, "expected '{' after actor state");
                while (!check(RBRACE) && !check(EOF)) {
                    if (sealedStateFields.size() >= 64)
                        throw error(peek(), "sealed actor state exceeds 64-field bound");
                    consume(LET, "sealed actor state requires private 'let' fields");
                    Ast.TypeRef type = parseTypeRef();
                    if (!type.arguments().isEmpty()
                            || (!type.name().equals("int") && !type.name().equals("bool")))
                        throw error(previous(), "sealed actor state v1 requires scalar int/bool fields");
                    String fieldName = consume(IDENT, "expected sealed actor state field name").lexeme();
                    if (sealedStateFields.stream().anyMatch(x -> x.name().equals(fieldName)))
                        throw error(previous(), "duplicate sealed actor state field '" + fieldName + "'");
                    if (!match(EQUAL, COLON))
                        throw error(peek(), "sealed actor state field needs a literal initializer");
                    Ast.Expr initializer = parseExpression();
                    if (!(initializer instanceof Ast.LiteralExpr literal))
                        throw error(previous(), "sealed actor state initializer must be a literal");
                    // A literal AST node alone is not enough: it could hold
                    // a String, float or bool in an int slot (or vice versa).
                    // With the physical state layout still unlowered, enforce
                    // the exact supported scalar representation at admission.
                    Object initialValue = literal.value();
                    if ((type.name().equals("int") && !(initialValue instanceof Long))
                            || (type.name().equals("bool") && !(initialValue instanceof Boolean)))
                        throw error(previous(), "sealed actor state initializer type must match "
                                + type.name());
                    consume(SEMICOLON, "sealed actor state field requires ';'");
                    sealedStateFields.add(new Ast.ProtectedStateField(fieldName, type, initializer));
                }
                consume(RBRACE, "unterminated sealed actor state container");
                if (sealedStateFields.isEmpty())
                    throw error(previous(), "sealed actor state must contain at least one field");
                continue;
            }
            if (isContextualIdentifier("int") && current + 1 < tokens.size()
                    && (tokens.get(current + 1).lexeme().equals("requestConcurrency")
                        || tokens.get(current + 1).lexeme().equals("messageConcurrency"))) {
                if (prefix.transport() == Ast.ActorTransport.GENERIC)
                    throw error(peek(), "requestConcurrency/messageConcurrency require an http/tcp/websocket actor");
                if (!annotations.isEmpty() || mods.visibilitySeen || mods.async
                        || mods.generator || mods.isStatic || mods.isAbstract
                        || mods.shared || mods.untrusted || mods.structural
                        || mods.nonLexical || mods.trapped)
                    throw error(peek(), "actor concurrency declarations cannot use modifiers or annotations");
                advance(); // int is a contextual type name
                String property = consume(IDENT, "expected actor concurrency property").lexeme();
                consume(EQUAL, "actor concurrency configuration requires a constant initializer");
                Token value = consume(INT, "actor concurrency must be a positive integer literal");
                int count;
                try {
                    count = Integer.parseInt(value.lexeme().replace("_", ""));
                } catch (NumberFormatException overflow) {
                    throw error(value, "actor concurrency limit exceeds the supported int range");
                }
                if (count < 1) throw error(value, "actor concurrency must be >= 1");
                consume(SEMICOLON, "actor concurrency configuration requires ';'");
                if (property.equals("requestConcurrency")) {
                    if (declaredRequestConcurrency) throw error(value, "duplicate requestConcurrency");
                    requestConcurrency = count;
                    declaredRequestConcurrency = true;
                } else {
                    if (declaredMessageConcurrency) throw error(value, "duplicate messageConcurrency");
                    messageConcurrency = count;
                    declaredMessageConcurrency = true;
                }
                continue;
            }
            if (isLegacyFnSpelling()) {
                if (mods.isStatic) throw error(peek(), "static actor functions use 'static fnc', not 'static fn'");
                throw error(peek(), "actor methods omit 'fn'/'fnc'; declare the method name directly");
            }
            if (isContextualIdentifier("constructor")) {
                throw error(peek(),
                        "actors do not declare constructors; actor state is initialized by the actor runtime");
            }
            if (check(DEFINE) || check(CLASS) || check(MODULE) || check(INTERFACE)
                    || check(TRAIT) || check(TYPE) || check(ACTOR) || check(ISOACTOR)) {
                throw error(peek(),
                        "declarations cannot be nested inside actors; declare nested types at file or module scope");
            }

            if (isBindingKind(peek().type())) {
                validateActorFieldModifiers(mods);
                fields.add(parseField(annotations, mods.visibility));
                continue;
            }
            if (isContextualIdentifier("final") && checkNext(IDENT)) {
                validateActorFieldModifiers(mods);
                fields.add(parseFinalActorField(annotations, mods.visibility));
                continue;
            }

            boolean explicitFnc = match(FNC);
            boolean explicitMethod = matchExplicitMethodMarker();
            if (explicitFnc && explicitMethod) {
                throw error(previous(), "an instance member cannot combine 'fnc' and 'method' markers");
            }
            if (explicitFnc || explicitMethod) {
                mods = mergeModifiers(mods, parseModifiers());
            }
            validateActorMethodModifiers(mods);
            if (mods.isStatic && explicitMethod) {
                throw error(previous(), "static actor functions use 'static fnc', not 'static method'");
            }
            if (mods.isStatic && !explicitFnc) {
                consume(FNC, "static actor functions must be declared with 'static fnc'");
            }
            // A return-type-first head leaves the return type at peek(), not
            // the method name. Do not let this spelling bypass the dedicated
            // lifecycle/CONTROL admission rules below.
            String returnFirstActorName = peekReturnFirstCallableName();
            if (returnFirstActorName != null && java.util.Set.of(
                    "tear_down", "handle_state", "on_panic", "on_throw",
                    "on_throws", "on_raise", "on_recover").contains(returnFirstActorName)) {
                throw error(peek(), "return-first actor lifecycle/CONTROL method '"
                        + returnFirstActorName + "' is reserved; use the checked legacy hook declaration");
            }
            // Reserved lifecycle and unhandled-failure methods must not be
            // silently treated as ordinary actor methods before runtime
            // teardown dispatch and typed exceptional-effect lowering exist.
            if (check(IDENT) && peek().lexeme().equals("tear_down")) {
                int lifecycleStartToken = current;
                Ast.MethodDecl teardown = parseMethod(annotations, mods);
                // Fail closed at admission, not after state has already been
                // mutated during shutdown. Nested lambda/defer awaits are also
                // disallowed in this first synchronous lifecycle slice.
                for (int i = lifecycleStartToken; i < current; i++) {
                    Token token = tokens.get(i);
                    if (token.type() == AWAIT
                            || (token.type() == RT
                                    && i + 1 < current
                                    && tokens.get(i + 1).lexeme().equals("cooperate"))) {
                        throw error(token,
                                "actor tear_down cannot await or cooperate until "
                                        + "scheduler-owned async teardown lowering exists");
                    }
                }
                // tear_down is a runtime-only lifecycle entry point, not an
                // ordinary user-invokable actor method. The first executable
                // slice is synchronous; suspend/async awaits require a pinned
                // teardown continuation and are rejected until implemented.
                if (teardown.isStatic() || teardown.async() || teardown.suspends()
                        || teardown.arity() != 0
                        || !teardown.genericParameters().isEmpty()
                        || !teardown.returnType().name().equals("void")
                        || !teardown.returnType().arguments().isEmpty()) {
                    throw error(previous(),
                            "actor tear_down must be a synchronous, nonstatic, "
                                    + "zero-argument, nongeneric void lifecycle method");
                }
                if (methods.stream().anyMatch(m -> m.name().equals("tear_down"))) {
                    throw error(previous(), "actor tear_down may be declared only once");
                }
                methods.add(teardown);
                continue;
            }
            // handle_state is reserved for a future intrinsic. A user
            // method with this name can spoof the critical-loan API, so no
            // actor may declare it while compiler-owned lowering is absent.
            if (check(IDENT) && peek().lexeme().equals("handle_state")) {
                throw error(peek(), "actor handle_state is compiler-owned; "
                        + "user-defined state-loan authority is forbidden");
            }
            // Preserve critical as explicit AST metadata. It is never an
            // ordinary method called 'critical', and generic execution is
            // still rejected before TypeChecker expansion or raw evaluation.
            if (isContextualIdentifier("critical")) {
                Token criticalToken = advance();
                if (!annotations.isEmpty() || mods.visibilitySeen || mods.async
                        || mods.suspends || mods.generator || mods.isStatic
                        || mods.isAbstract || mods.shared || mods.untrusted
                        || mods.structural || mods.nonLexical || mods.trapped) {
                    throw error(criticalToken,
                            "critical actor method cannot have modifiers or annotations");
                }
                int criticalBodyStartToken = current;
                Ast.MethodDecl parsed = parseMethod(List.of(), mods);
                // No nested await or cooperative yield can run while an
                // actor-owned exclusive state loan is held. Scan the complete
                // parsed method token span, including lambda/defer/loop bodies;
                // merely checking the outer method's suspends flag is unsafe.
                // Once checked MIR exists it must enforce this transitively
                // through the complete call graph as well.
                for (int i = criticalBodyStartToken; i < current; i++) {
                    Token token = tokens.get(i);
                    if (token.type() == AWAIT
                            || (token.type() == RT && i + 1 < current
                                    && tokens.get(i + 1).lexeme().equals("cooperate"))) {
                        throw error(token,
                                "critical state loan cannot await or cooperate");
                    }
                }
                if (parsed.name().equals("receive") || parsed.name().equals("on_start")
                        || parsed.name().equals("tear_down") || parsed.name().equals("handle_state")
                        || parsed.name().equals("on_panic")) {
                    throw error(criticalToken,
                            "critical actor method cannot define runtime lifecycle or intrinsics");
                }
                if (parsed.body().isEmpty() || parsed.isAbstract()
                        || parsed.isStatic() || parsed.async() || parsed.suspends()
                        || parsed.structural() || !parsed.genericParameters().isEmpty()
                        || !parsed.effects().equals(Ast.EffectSpec.none())) {
                    throw error(criticalToken,
                            "critical method requires non-suspending checked body");
                }
                methods.add(new Ast.MethodDecl(
                        parsed.name(), parsed.visibility(), false, false,
                        false, false, false, parsed.explicitReceiverType(),
                        parsed.genericParameters(), parsed.parameters(), parsed.returnType(),
                        parsed.annotations(), parsed.body(), parsed.effects(), true));
                continue;
            }
            if (check(IDENT) && peek().lexeme().equals("on_panic")) {
                throw error(peek(), "actor-local on_panic is forbidden: "
                        + "panic goes directly to the supervisor CONTROL lane");
            }
            if (check(IDENT) && (peek().lexeme().equals("on_throw")
                    || peek().lexeme().equals("on_throws")
                    || peek().lexeme().equals("on_raise")
                    || peek().lexeme().equals("on_recover"))) {
                throw error(peek(), "actor unhandled-failure on_* handlers require "
                        + "compiler-enforced exhaustiveness and CONTROL dispatch; "
                        + "source handlers are not yet enabled");
            }
            methods.add(parseMethod(annotations, mods));
        }

        consume(terminator, braceStyle
                ? "expected '}' to close actor " + name
                : "expected 'end' to close actor " + name);
        if (prefix.serial() && (requestConcurrency != 1 || messageConcurrency != 1)) {
            throw error(previous(), "serial actors require requestConcurrency = 1 and messageConcurrency = 1");
        }
        if (declaredSealedState && !fields.isEmpty())
            throw error(previous(), "sealed actor state cannot mix ordinary actor fields");
        return new Ast.ClassDecl(
                name, visibility, false, actorKind, generics, parents, interfaces, fields, null, methods,
                new Ast.ActorConcurrency(prefix.transport(), requestConcurrency, messageConcurrency,
                        prefix.serial()),
                sealedStateFields, new Ast.ActorMessageContract(receives, sends));
    }

    private void validateActorFieldModifiers(Modifiers modifiers) {
        if (modifiers.visibility == Ast.Visibility.PUBLIC) {
            throw error(peek(), "actor state fields are private; expose state through actor methods");
        }
        if (modifiers.async || modifiers.suspends || modifiers.generator || modifiers.structural || modifiers.nonLexical
                || modifiers.trapped || modifiers.isStatic || modifiers.isAbstract || modifiers.shared || modifiers.untrusted) {
            throw error(peek(), "actor state fields do not accept callable/static/actor modifiers");
        }
    }

    private void validateActorMethodModifiers(Modifiers modifiers) {
        if (modifiers.generator || modifiers.nonLexical || modifiers.trapped || modifiers.isAbstract
                || modifiers.shared || modifiers.untrusted) {
            throw error(peek(), "actor methods do not accept generator, nlex, abstract, shared, or untrusted modifiers");
        }
    }

    private void rejectForgedCompilerProtocol(String name) {
        // Trait, interface and contract declarations all participate in
        // nominal conformance. No spelling may forge an actor/ownership proof.
        if (java.util.Set.of("Sendable", "Shareable", "Copy", "Drop",
                "Receives", "Supervisor", "ActorLifecycle").contains(name)) {
            throw error(previous(), "protocol '" + name
                    + "' is compiler/runtime-owned; guest declarations cannot grant safety authority");
        }
    }

    private Ast.InterfaceDecl parseContract(Ast.Visibility visibility) {
        Ast.InterfaceDecl iface = parseInterface(visibility, true);
        return new Ast.InterfaceDecl(iface.name(), iface.visibility(), iface.genericParameters(), iface.parents(), iface.members(), true);
    }

    private Ast.InterfaceDecl parseDefinedInterface(Ast.Visibility visibility, Token.Type kind) {
        if (kind != TRAIT && kind != INTERFACE) {
            throw error(previous(), "expected trait or interface declaration kind");
        }
        Ast.InterfaceDecl declaration = parseInterface(visibility);
        return new Ast.InterfaceDecl(
                declaration.name(), declaration.visibility(), declaration.genericParameters(),
                declaration.parents(), declaration.members(), false,
                kind == TRAIT ? Ast.InterfaceKind.TRAIT : Ast.InterfaceKind.INTERFACE);
    }

    private Ast.InterfaceDecl parseInterface(Ast.Visibility visibility) { return parseInterface(visibility, false); }

    private Ast.InterfaceDecl parseInterface(Ast.Visibility visibility, boolean moduleContract) {
        String name = consumeTypeDeclarationName("expected interface name");
        // Deny compiler/runtime authority at the shared declaration parser,
        // before parsing parents or members. Every trait/interface/contract
        // spelling (including future parser entrypoints) passes this gate.
        rejectForgedCompilerProtocol(name);
        List<String> generics = parseGenericParameters();
        List<Ast.TypeRef> parents = match(EXTENDS) ? parseTypeRefList() : List.of();
        boolean braceStyle = match(LBRACE);
        if (!braceStyle) match(AS);
        Token.Type terminator = braceStyle ? RBRACE : END;

        List<Ast.InterfaceMember> members = new ArrayList<>();
        while (!check(terminator) && !check(EOF)) {
            List<Ast.Annotation> memberAnnotations = parseAnnotations();
            Modifiers memberModifiers = parseModifiers();
            if (match(DEFINE)) {
                memberModifiers = mergeModifiers(memberModifiers, parseModifiers());
            }
            if (check(MODULE) || check(CLASS) || check(INTERFACE)
                    || check(TRAIT) || check(TYPE)) {
                throw error(peek(),
                        "named type and module declarations are not permitted inside interfaces, traits, or contracts");
            }

            // A module contract may require the presence of a public nominal
            // actor type. This is a type requirement, not a callable or a
            // second actor declaration. It must not confer state authority.
            if (moduleContract && match(ACTOR)) {
                validateNoModifiers(memberModifiers, "module contract actor requirements");
                if (!memberAnnotations.isEmpty()) {
                    throw error(previous(), "contract actor requirements do not accept annotations");
                }
                String actorName = consumeTypeDeclarationName("expected actor type name in module contract");
                consumeMemberTerminator(terminator, "contract actor requirement should end with ';'");
                members.add(new Ast.InterfaceActorDecl(actorName));
                continue;
            }
            if (!moduleContract && check(ACTOR)) {
                throw error(peek(), "actor requirements belong only in module contracts");
            }
            // A receiver method in a module contract requires an actor's
            // existing mailbox-dispatched instance method. This spelling is
            // deliberately NOT an executable free-function declaration.
            // Keep "mtd" available as a type/identifier in field positions.
            boolean actorMethodHead = isContextualIdentifier("mtd")
                    && current + 2 < tokens.size()
                    && tokens.get(current + 1).type() == IDENT
                    && tokens.get(current + 2).type() == LPAREN;
            if (moduleContract && actorMethodHead) {
                advance(); // contextual mtd
                validateOnlyVisibilityModifiers(memberModifiers, "module contract receiver requirements");
                if (!memberAnnotations.isEmpty()) {
                    throw error(previous(), "contract receiver requirements do not accept annotations");
                }
                String methodName = consumeCallableName("expected receiver method name");
                consume(LPAREN, "expected '(' before nominal actor receiver");
                Ast.TypeRef receiver = parseTypeRef();
                if (!receiver.arguments().isEmpty() || receiver.inferArguments()
                        || receiver.isBorrow()) {
                    throw error(previous(), "contract receiver must name a non-generic nominal actor");
                }
                consume(IDENT, "expected actor receiver parameter name");
                consume(RPAREN, "expected ')' after actor receiver");
                consume(LPAREN, "expected '(' before receiver method parameters");
                List<Ast.Param> parameters = parseParametersUntil(RPAREN);
                consume(RPAREN, "expected ')' after receiver method parameters");
                if (parameters.stream().anyMatch(Ast.Param::structural)) {
                    throw error(previous(), "structural arguments cannot cross an actor receiver boundary");
                }
                Ast.TypeRef returns = match(FAT_ARROW, COLON) ? parseTypeRef() : omittedNamedCallableReturn();
                if (!parseCheckedEffects().isEmpty()) {
                    throw error(previous(), "receiver contract checked effects require typed actor-effect lowering");
                }
                consumeMemberTerminator(terminator, "actor receiver requirement should end with ';'");
                members.add(new Ast.InterfaceActorMethodDecl(
                        receiver.name(), methodName, memberModifiers.visibility, parameters, returns));
                continue;
            }
            if (!moduleContract && actorMethodHead) {
                throw error(peek(), "actor receiver requirements belong only in module contracts");
            }
            if (isLegacyFnSpelling()) {
                throw error(peek(), "interface functions are declared with 'fnc', not 'fn'");
            }
            // Trait/interface signatures allow the canonical bare instance
            // method form: get_future(): Future<T>;
            if (match(FNC) || (check(IDENT) && checkNext(LPAREN))) {
                memberModifiers = mergeModifiers(memberModifiers, parseModifiers());
                validateInterfaceCallableModifiers(memberModifiers);
                CallableStructural callableStructural = normalizeCallableStructural(memberAnnotations, memberModifiers.structural);
                if (!callableStructural.annotations().isEmpty()) {
                    throw error(previous(),
                            "interface-member annotations are not represented by the current AST; "
                                    + "only @structural is accepted here and unsupported annotations must not be silently discarded");
                }
                // Newable may declare an explicitly named factory contract.
                // Keep `new` reserved as a local identifier and constructor
                // keyword; only declaration-member names admit this token.
                String memberName = match(NEW)
                        ? "new"
                        : consumeCallableName("expected interface function name");
                List<String> memberGenerics = parseGenericParameters();
                consume(LPAREN, "expected '(' after interface function name");
                List<Ast.Param> params = parseParametersUntil(RPAREN);
                consume(RPAREN, "expected ')' after interface parameters");
                Ast.TypeRef returns = match(FAT_ARROW, COLON) ? parseTypeRef() : omittedNamedCallableReturn();
                Ast.EffectSpec effects = parseCheckedEffects();
                consumeMemberTerminator(terminator, "interface function signature should end with ';'");
                members.add(new Ast.InterfaceFunctionDecl(memberName, callableStructural.enabled(), memberGenerics, params, returns, effects));
                continue;
            }

            validateNoModifiers(memberModifiers, "interface fields");
            if (!memberAnnotations.isEmpty()) {
                throw error(previous(),
                        "interface-field annotations are not represented by the current AST and must not be silently discarded");
            }

            // A bare module-contract field requires readable access only.
            // Explicit bindings retain the current rebindability/referent model.
            Ast.BindingKind fieldBindingKind = moduleContract ? null : Ast.BindingKind.VAL;
            boolean fieldMutableReferent = !moduleContract;
            if (isBindingKind(peek().type())) {
                fieldBindingKind = parseBindingKind();
                fieldMutableReferent = parseReferentMutability(fieldBindingKind);
            }

            if (check(IDENT) && checkNext(COLON)) {
                String fieldName = advance().lexeme();
                consume(COLON, "expected ':' after interface field name");
                Ast.TypeRef type = parseTypeRef();
                consumeMemberTerminator(terminator, "interface field signature should end with ';'");
                members.add(new Ast.InterfaceFieldDecl(
                        fieldName, type, fieldBindingKind, fieldMutableReferent));
                continue;
            }

            Ast.TypeRef type = parseTypeRef();
            String fieldName = consume(IDENT, "expected interface field name").lexeme();
            consumeMemberTerminator(terminator, "interface field signature should end with ';'");
            members.add(new Ast.InterfaceFieldDecl(
                    fieldName, type, fieldBindingKind, fieldMutableReferent));
        }

        consume(terminator, braceStyle ? "expected '}' to close interface " + name : "expected 'end' to close interface " + name);
        return new Ast.InterfaceDecl(name, visibility, generics, parents, members, moduleContract);
    }

    private List<Ast.TypeRef> parseTypeRefList() {
        List<Ast.TypeRef> refs = new ArrayList<>();
        do refs.add(parseTypeRef()); while (match(COMMA));
        return refs;
    }

    private Ast.FieldDecl parseField(List<Ast.Annotation> annotations, Ast.Visibility visibility) {
        rejectNonCallableReturnAnnotation(annotations, "fields");
        rejectCallableStructuralAnnotation(annotations, "fields");
        Ast.BindingKind kind = parseBindingKind();
        rejectConstTypeDeclaration(kind);
        boolean mutableReferent = parseReferentMutability(kind);
        Ast.TypeRef type = null;
        String name;
        if (check(IDENT) && checkNext(EQUAL)) {
            name = advance().lexeme();
        } else {
            type = parseTypeRef();
            name = consume(IDENT, "expected field name").lexeme();
        }
        Ast.Expr initializer = match(EQUAL) ? parseExpression() : null;
        if (type == null && initializer == null) {
            throw error(previous(), "inferred field '" + name + "' requires an initializer");
        }
        consumeExpressionStatementTerminator(initializer, "field declaration should end with ';'");
        if (type != null) type = applyTypeMetadataAnnotations(type, annotations, false);
        return new Ast.FieldDecl(name, visibility, kind, type, annotations, initializer, mutableReferent);
    }

    private boolean looksLikeTypeFirstClassField() {
        int mark = current;
        try {
            match(MUT); // Explicit mutable referent is independent of finality.
            parseTypeRef();
            if (!check(IDENT)) return false;
            advance();
            return check(COLON) || check(EQUAL);
        } catch (IllegalArgumentException ex) {
            return false;
        } finally {
            current = mark;
        }
    }

    private Ast.FieldDecl parseTypeFirstClassField(
            List<Ast.Annotation> annotations, Ast.Visibility visibility) {
        rejectNonCallableReturnAnnotation(annotations, "fields");
        rejectCallableStructuralAnnotation(annotations, "fields");
        boolean isFinal = matchContextualIdentifier("final");
        boolean mutableReferent = match(MUT);
        Ast.TypeRef type = applyTypeMetadataAnnotations(parseTypeRef(), annotations, false);
        String name = consume(IDENT, "expected class field name after type").lexeme();
        if (!match(COLON, EQUAL)) {
            throw error(peek(), "class field initializer requires ':' or '='");
        }
        Ast.Expr initializer = parseExpression();
        consumeExpressionClassFieldTerminator(initializer, "field declaration should end with ';'");
        // Slot reassignment and mutation through its stored referent are
        // separate capabilities. 'final' does NOT silently grant mutable
        // referent access; only an explicit 'mut' modifier requests it.
        // AttoLang v2: an omitted class-field binder means val, irrespective
        // of the referent's mutability. Historical unversioned .ores parsing
        // remains intentionally unchanged until an explicit migration.
        Ast.BindingKind kind = isFinal || canonicalAttoSource
                ? Ast.BindingKind.VAL : Ast.BindingKind.LET;
        return new Ast.FieldDecl(name, visibility, kind, type, annotations, initializer, mutableReferent);
    }

    private Ast.FieldDecl parseColonField(List<Ast.Annotation> annotations, Ast.Visibility visibility) {
        rejectNonCallableReturnAnnotation(annotations, "fields");
        rejectCallableStructuralAnnotation(annotations, "fields");
        String name = consume(IDENT, "expected field name").lexeme();
        consume(COLON, "expected ':' after field name");
        Ast.TypeRef type = parseTypeRef();
        Ast.Expr initializer = match(EQUAL) ? parseExpression() : null;
        Ast.BindingKind kind = hasAnnotation(annotations, "FromJson") ? Ast.BindingKind.LET : Ast.BindingKind.VAL;
        type = applyTypeMetadataAnnotations(type, annotations, false);
        consumeExpressionClassFieldTerminator(initializer, "field declaration should end with ';'");
        return new Ast.FieldDecl(name, visibility, kind, type, annotations, initializer);
    }

    /** Actor-state 'final name: Type' is a const/immutable field spelling. */
    private Ast.FieldDecl parseFinalActorField(List<Ast.Annotation> annotations, Ast.Visibility visibility) {
        rejectNonCallableReturnAnnotation(annotations, "actor fields");
        rejectCallableStructuralAnnotation(annotations, "fields");
        consumeContextualIdentifier("final", "expected 'final' actor field");
        String name = consume(IDENT, "expected field name after 'final'").lexeme();
        consume(COLON, "expected ':' after final field name");
        Ast.TypeRef type = applyTypeMetadataAnnotations(parseTypeRef(), annotations, false);
        Ast.Expr initializer = match(EQUAL) ? parseExpression() : null;
        consumeExpressionClassFieldTerminator(initializer, "final actor field should end with ';'");
        return new Ast.FieldDecl(name, visibility, Ast.BindingKind.CONST,
                type, annotations, initializer, false);
    }

    private Ast.FieldDecl parseModuleBinding(List<Ast.Annotation> annotations, Ast.Visibility visibility) {
        rejectNonCallableReturnAnnotation(annotations, "module bindings");
        rejectCallableStructuralAnnotation(annotations, "module bindings");
        Ast.BindingKind kind = parseBindingKind();
        rejectConstTypeDeclaration(kind);
        boolean mutableReferent = parseReferentMutability(kind);
        Ast.TypeRef type = null;
        String name;
        boolean functionShorthand = match(FNC);
        if (functionShorthand && (kind != Ast.BindingKind.CONST || mutableReferent)) {
            throw error(previous(), "lambda binding shorthand requires 'const fnc'");
        }
        if (functionShorthand) name = consume(IDENT, "expected function binding name").lexeme();
        else if (looksLikeNamedCallableBindingSignature()) {
            name = advance().lexeme();
            type = parseNamedCallableBindingSignature();
        } else if (check(IDENT) && checkNext(EQUAL)) name = advance().lexeme();
        else {
            type = parseTypeRef();
            name = consume(IDENT, "expected binding name").lexeme();
        }
        if (type != null) type = applyTypeMetadataAnnotations(type, annotations, false);
        consume(EQUAL, "module bindings require an initializer");
        Ast.Expr initializer = parseExpression();
        if (functionShorthand && !(initializer instanceof Ast.LambdaExpr)) {
            throw error(previous(), "'const fnc' requires a lambda initializer");
        }
        consumeExpressionStatementTerminator(initializer, "module binding should end with ';'");
        return new Ast.FieldDecl(name, visibility, kind, type, annotations, initializer, mutableReferent);
    }

    private Ast.MethodDecl parseMethod(List<Ast.Annotation> annotations, Modifiers mods) {
        return parseMethod(annotations, mods, false);
    }

    private Ast.MethodDecl parseMethod(List<Ast.Annotation> annotations, Modifiers mods,
                                       boolean enclosingAbstractClass) {
        CallableStructural structural = normalizeCallableStructural(annotations, mods.structural);
        annotations = structural.annotations();
        Ast.TypeRef prefixReturnType = parseReturnFirstTypeIfPresent();
        String name = parseMethodName();
        List<String> generics = parseGenericParameters();
        consume(LPAREN, "expected '(' after method name");

        Ast.TypeRef receiverType = null;
        List<Ast.Param> params;
        boolean mutableReceiver = check(MUT) && checkNext(SELF);
        if (mods.isStatic && (check(SELF) || mutableReceiver)) {
            throw error(peek(), "static class functions do not have a self receiver");
        }

        // A leading receiver is never a value parameter or overload discriminator:
        //   method(self, Type value)       -> default read-only receiver
        //   method(self)                   -> default read-only receiver, zero args
        //   method(mut self, Type value)   -> mutable receiver
        // Legacy two-parenthesis forms remain supported:
        //   method(mut self)(params)       -> mutable receiver
        //   method(self Foo)(params)       -> explicitly typed receiver
        // Ordinary instance methods without a receiver marker also receive self.
        int parameterStart = current;
        boolean explicitReceiver = false;
        boolean inlineReceiver = false;
        boolean inlineReceiverComma = false;
        if (mutableReceiver) {
            advance(); // mut
            consume(SELF, "mutable receiver syntax is 'mut self'");
            if (match(COMMA)) {
                inlineReceiver = true;
                inlineReceiverComma = true;
            } else if (check(RPAREN) && !checkNext(LPAREN)) {
                inlineReceiver = true;
            } else {
                Ast.TypeRef target = check(RPAREN) ? Ast.TypeRef.simple("self") : parseTypeRef();
                receiverType = Ast.TypeRef.borrowed(target, true);
                explicitReceiver = true;
            }
            if (inlineReceiver) {
                receiverType = Ast.TypeRef.borrowed(Ast.TypeRef.simple("self"), true);
            }
        } else if (check(SELF)) {
            advance();
            if (match(COMMA)) {
                inlineReceiver = true;
                inlineReceiverComma = true;
            } else if (check(RPAREN) && !checkNext(LPAREN)) {
                inlineReceiver = true;
            } else {
                Ast.TypeRef candidateReceiver = parseTypeRef();
                if (check(RPAREN) && checkNext(LPAREN)) {
                    receiverType = candidateReceiver;
                    explicitReceiver = true;
                } else {
                    current = parameterStart;
                }
            }
        }

        if (inlineReceiver) {
            if (inlineReceiverComma && check(RPAREN)) {
                throw error(peek(), "receiver 'self' must be followed by a value parameter after ','");
            }
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after inline self receiver and method parameters");
        } else if (explicitReceiver) {
            consume(RPAREN, "expected ')' after explicit self receiver");
            consume(LPAREN, mutableReceiver
                    ? "explicit mutable receiver form is method(mut self)(params)"
                    : "explicit receiver form is method(self Type)(params)");
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after method parameters");
        } else {
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after method parameters");
        }

        // A keyword-delimited method requires the explicit slim-arrow return
        // spelling and a contiguous '::' header/body boundary. Braced
        // methods continue accepting either return-type spelling.
        boolean arrowMethodBody = check(ARROW);
        Ast.TypeRef returnType = prefixReturnType == null
                ? parseReturnType(annotations)
                : validateReturnFirstType(prefixReturnType, annotations);
        Ast.EffectSpec effects = parseCheckedEffects();
        // An abstract class may declare required slots as `name(args): Type;`.
        // Such signatures are abstract, but remain private unless explicitly pub.
        // Their identity is still name + arity only.
        // Outside abstract classes, a stray semicolon is never a method body.
        boolean implicitAbstract = check(SEMICOLON) && !mods.isAbstract && enclosingAbstractClass;
        if (check(SEMICOLON) && !mods.isAbstract && !enclosingAbstractClass) {
            throw error(peek(), "signature-only method '" + name
                    + "' requires an abstract class or explicit 'abstract' modifier");
        }
        List<Ast.Stmt> body;
        if (prefixReturnType != null && (mods.isAbstract || implicitAbstract)) {
            throw error(peek(), "return-first methods require an executable callable body");
        }
        if (prefixReturnType != null) {
            body = parseReturnFirstCallableBody(!effects.isEmpty());
        } else if (mods.isAbstract || implicitAbstract) {
            consumeStatementTerminator("abstract method should end with ';'");
            body = List.of();
        } else if (match(DOUBLE_COLON)) {
            if (!arrowMethodBody) {
                throw error(previous(), "end-delimited methods require '-> ReturnType ::'");
            }
            if (attoCallableVersion != null) {
                body = parseVersionedCommaTailBody();
            } else {
                body = new ArrayList<>();
                while (!check(END) && !check(EOF)) body.add(parseStatement());
                consume(END, "expected 'end' to close method '" + name + "'");
            }
        } else if (attoCallableVersion == AttoCallableVersion.V1
                && arrowMethodBody && !check(LBRACE)) {
            body = parseVersionedCommaTailBody();
        } else if (attoCallableVersion == AttoCallableVersion.V2
                && arrowMethodBody && !check(LBRACE)) {
            body = parseUntil(END);
            consume(END, "expected 'end' after v2 statement method body");
        } else body = parseBlock();
        return new Ast.MethodDecl(name, mods.visibility, mods.isStatic, mods.isAbstract || implicitAbstract, mods.async, mods.suspends, structural.enabled(),
                receiverType, generics, params, returnType, annotations, body, effects);
    }

    private String parseMethodName() {
        // `new` remains reserved as an expression keyword, but an explicit
        // user-authored method/factory signature may name it. This alone does
        // not synthesize a static factory or grant Newable implementation.
        if (match(NEW)) {
            return "new";
        }
        if (match(LBRACKET)) {
            String namespace = consume(IDENT, "expected symbol namespace").lexeme();
            consume(DOT, "expected '.' in symbol method");
            String symbol = consume(IDENT, "expected symbol name").lexeme();
            consume(RBRACKET, "expected ']' after symbol method");
            if (!namespace.equals("Symbol")) throw error(previous(), "symbol methods must use Symbol.<name>");
            return namespace + "." + symbol;
        }
        return consumeCallableName("expected method name (methods omit 'fnc')");
    }


    /**
     * '_' is a wildcard/inference hole, not a bindable user type identity.
     * Keep the same admission boundary for all nominal/structural type names
     * and for forward declarations to prevent accidental namespace aliases.
     */
    private String consumeTypeDeclarationName(String diagnostic) {
        Token name = consume(IDENT, diagnostic);
        if ("_".equals(name.lexeme())) {
            throw error(name, "'_' cannot be used as a declared type name");
        }
        return name.lexeme();
    }

    private ForwardType parseForwardType(List<Ast.Annotation> annotations, Modifiers before) {
        Token start = advance(); // contextual 'declare'; do not reserve it globally
        if (!annotations.isEmpty() || before.visibilitySeen || before.async || before.suspends
                || before.generator || before.structural || before.nonLexical || before.trapped
                || before.isStatic || before.isAbstract || before.shared || before.untrusted) {
            throw error(start, "forward declarations must start with 'declare'; visibility follows it");
        }
        Modifiers after = parseModifiers();
        if (after.async || after.suspends || after.generator || after.structural
                || after.nonLexical || after.trapped || after.isStatic || after.isAbstract
                || after.shared || after.untrusted) {
            throw error(start, "forward declarations accept only private/pub visibility");
        }
        if (!match(TYPE, TRAIT, INTERFACE)) {
            throw error(peek(), "'declare' supports only type, trait or interface");
        }
        Token.Type kind = previous().type();
        String name = consumeTypeDeclarationName("expected forward type name");
        List<String> generics = parseGenericParameters();
        consume(SEMICOLON, "forward declaration must end with ';' and cannot supply a body");
        return new ForwardType(kind, name, after.visibility, List.copyOf(generics));
    }

    private static String definedName(Ast.Decl declaration) {
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.name();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.name();
        if (declaration instanceof Ast.ClassDecl klass) return klass.name();
        if (declaration instanceof Ast.FunctionDecl fn) return fn.name();
        if (declaration instanceof Ast.FieldDecl field) return field.name();
        return null;
    }

    private void validateForwardTypes(List<ForwardType> declarations, List<Ast.Decl> definitions, String scope) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ForwardType forward : declarations) {
            if (!seen.add(forward.name())) {
                throw new IllegalArgumentException("duplicate forward declaration '" + forward.name() + "' in " + scope);
            }
            Ast.Decl definition = null;
            for (Ast.Decl candidate : definitions) {
                // Value-side functions and bindings occupy a distinct namespace.
                // They cannot satisfy a forward type, but also must not make an
                // otherwise valid type signature spuriously ambiguous.
                if (!(candidate instanceof Ast.TypeAliasDecl
                        || candidate instanceof Ast.InterfaceDecl
                        || candidate instanceof Ast.ClassDecl)) continue;
                if (!forward.name().equals(definedName(candidate))) continue;
                if (definition != null) {
                    throw new IllegalArgumentException("ambiguous forward type '" + forward.name()
                            + "': multiple definitions in " + scope);
                }
                definition = candidate;
            }
            if (definition == null) {
                throw new IllegalArgumentException("unresolved forward declaration '"
                        + forward.name() + "' in " + scope + "; cross-file forwards are not supported");
            }
            Token.Type actual;
            List<String> generics;
            Ast.Visibility visibility;
            if (definition instanceof Ast.TypeAliasDecl alias) {
                actual = TYPE;
                generics = alias.genericParameters();
                visibility = alias.visibility();
            } else if (definition instanceof Ast.InterfaceDecl iface) {
                actual = switch (iface.declarationKind()) {
                    case CONTRACT -> CONTRACT;
                    case TRAIT -> TRAIT;
                    case INTERFACE -> INTERFACE;
                };
                generics = iface.genericParameters();
                visibility = iface.visibility();
            } else {
                throw new IllegalArgumentException("forward type '" + forward.name()
                        + "' resolved to a value declaration instead of a type/trait/interface in " + scope);
            }
            if (actual != forward.kind()) {
                throw new IllegalArgumentException("forward declaration kind mismatch for '"
                        + forward.name() + "' in " + scope + ": expected " + forward.kind()
                        + " but definition is " + actual);
            }
            if (!generics.equals(forward.generics())) {
                throw new IllegalArgumentException("forward declaration generic parameter mismatch for '"
                        + forward.name() + "' in " + scope);
            }
            if (visibility != forward.visibility()) {
                throw new IllegalArgumentException("forward declaration visibility mismatch for '"
                        + forward.name() + "' in " + scope + "; cannot widen or narrow visibility");
            }
        }
    }

    private Ast.TypeAliasDecl parseTypeAlias(Ast.Visibility visibility) {
        // The contextual marker must be followed by a name. In particular,
        // legacy declarations such as `type alias = int;` continue to use
        // "alias" as their actual identifier rather than a new reserved word.
        boolean namedAlias = check(IDENT) && peek().lexeme().equals("alias") && checkNext(IDENT);
        if (namedAlias) advance();
        String name = consumeTypeDeclarationName("expected type alias name");
        List<String> generics = parseGenericParameters();
        if (namedAlias) {
            consume(AS, "expected 'as' after named type alias");
        } else {
            consume(EQUAL, "expected '=' in type alias");
        }
        boolean directStruct = check(STRUCT) && checkNext(LBRACE);
        Ast.TypeRef target = parseTypeRef();
        if (directStruct && isContextualIdentifier("with") &&
                (checkNext(LBRACE) || (current + 1 < tokens.size()
                    && List.of("constructor", "mtd", "pub", "private").contains(tokens.get(current + 1).lexeme())))) {
            parseStructAttachmentTarget(name, visibility);
        }
        // A refinement is not an ordinary transparent alias: never erase
        // its predicate at this parser boundary. Enable this syntax only
        // together with typed proof-preserving rules + Zorde lowering.
        if (isContextualIdentifier("with") || check(AS)) {
            throw error(peek(), "refined type alias requires checked constraint HIR, "
                    + "Zorde exact-value validation and proof-preserving runtime lowering; "
                    + "with/as constraints are not yet executable");
        }
        consumeStatementTerminator("type alias should end with ';'");
        return new Ast.TypeAliasDecl(name, visibility, generics, target);
    }

    private boolean hasDirectStructAttachment() {
        if (!check(STRUCT) || !checkNext(IDENT) || current + 2 >= tokens.size()
                || tokens.get(current + 2).type() != LBRACE) return false;
        int depth = 0;
        for (int i = current + 2; i < tokens.size(); i++) {
            Token.Type kind = tokens.get(i).type();
            if (kind == LBRACE) depth++;
            else if (kind == RBRACE && --depth == 0)
                return i + 1 < tokens.size() && tokens.get(i + 1).lexeme().equals("with");
        }
        return false;
    }

    private Ast.Decl parseDirectStructAttachment(Ast.Visibility visibility) {
        consume(STRUCT, "expected struct after define");
        String name = consumeTypeDeclarationName("expected struct name");
        parseClosedAggregateType(true);
        parseStructAttachmentTarget(name, visibility);
        throw error(peek(), "attached struct value ABI required before execution");
    }

    /** Validate grouped/chained struct member headers through the ordinary
     * callable parser; never return a transparent alias after discarding them.
     * A real nominal struct member AST and value ABI are still required. */
    private void parseStructAttachmentTarget(String name, Ast.Visibility visibility) {
        Token attachment = peek();
        List<Ast.ConstructorDecl> constructors = new ArrayList<>();
        List<Ast.MethodDecl> methods = new ArrayList<>();
        do {
            advance(); // contextual 'with'
            boolean grouped = match(LBRACE);
            if (grouped && check(RBRACE)) {
                throw error(peek(), "struct with block requires a constructor or mtd");
            }
            do {
                if (constructors.size() + methods.size() >= 256) {
                    throw error(peek(), "too many attached struct members");
                }
                Modifiers mods = parseModifiers();
                validateOnlyVisibilityModifiers(mods, "attached struct target members");
                if (isContextualIdentifier("constructor")) {
                    Token member = peek();
                    Ast.ConstructorDecl ctor = parseConstructor(List.of(), mods, name, visibility, false);
                    if (constructors.stream().anyMatch(prior ->
                            sameAttachedParameterTypes(prior.parameters(), ctor.parameters()))) {
                        throw error(member, "duplicate attached struct constructor signature");
                    }
                    constructors.add(ctor);
                } else if (isContextualIdentifier("mtd")) {
                    Token member = advance();
                    Ast.MethodDecl method = parseMethod(List.of(), mods);
                    if (methods.stream().anyMatch(prior -> prior.name().equals(method.name())
                            && sameAttachedParameterTypes(prior.parameters(), method.parameters()))) {
                        throw error(member, "duplicate attached struct method signature");
                    }
                    methods.add(method);
                } else {
                    throw error(peek(), "expected attached struct constructor or mtd");
                }
            } while (grouped && !check(RBRACE) && !check(EOF));
            if (grouped) consume(RBRACE, "expected '}' after struct with members");
        } while (isContextualIdentifier("with"));
        throw error(attachment, "attached struct members parsed; nominal value ABI and checked initialization required before execution");
    }

    private boolean sameAttachedParameterTypes(List<Ast.Param> left, List<Ast.Param> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (!sameType(left.get(i).type(), right.get(i).type())) return false;
        }
        return true;
    }

    private List<Ast.Annotation> parseAnnotations() {
        List<Ast.Annotation> result = new ArrayList<>();
        // The current compiler has no registered repeatable annotation schema.
        // Reject duplicates before any target-specific transformation or AST retention.
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (match(AT)) {
            String name = consume(IDENT, "expected annotation name").lexeme();
            // These names claim code generation, target selection or ABI effects.
            // Until a typed annotation registry and AOT lowering exist, retaining
            // them as inert metadata would silently change program semantics.
            if (List.of("derive", "Derive", "repr", "Repr", "align",
                    "cfg", "Cfg", "inline", "Inline", "expand", "GenerateBuilder",
                    "GenerateSchema", "GenerateClient", "ActorMessage",
                    "PipelineStage", "RequiresState",
                    "Critical", "RequiresPermission", "Permission",
                    "GrantPermission", "Unsafe", "Trusted", "Untrusted",
                    "AllowAll", "CallerRole", "CallableBy", "ActorMiddleware",
                    "ActorMaxThreads",
                    // An injected state handle is a capability, not inert metadata.
                    // Reject before parsing nested arguments such as
                    // @Inject(MutableStateContainer()) to avoid parse-error
                    // misclassification and, more importantly, silent admission.
                    "Inject", "StateBacked", "MutableStateContainer",
                    "StateContainer", "DefaultArgVals").contains(name)) {
                throw error(previous(), "@" + name
                        + " requires compile-time semantic validation and guest-AOT lowering; not implemented");
            }
            // A mutable-state injection is a compiler effect, never inert
            // annotation metadata. Reserve only this requested intrinsic;
            // other annotation names keep their existing source behavior.
            if (name.equals("Inject") && (check(LPAREN) || check(LT))
                    && current + 1 < tokens.size()
                    && tokens.get(current + 1).type() == IDENT
                    && tokens.get(current + 1).lexeme().equals("MutableStateContainer")) {
                throw error(previous(), "@Inject(MutableStateContainer(...)) requires "
                        + "compiler-checked private state-region lowering; not implemented");
            }
            List<Ast.TypeRef> args = new ArrayList<>();
            Token.Type close = null;
            if (match(LT)) close = GT;
            else if (match(LPAREN)) close = RPAREN;
            if (close != null) {
                if (!check(close)) {
                    do {
                        if (check(IDENT) && checkNext(EQUAL)) {
                            String parameterName = advance().lexeme();
                            consume(EQUAL, "expected '=' after named annotation parameter");
                            args.add(Ast.TypeRef.namedParameter(parameterName, parseMetaValue()));
                        } else {
                            args.add(parseMetaValue());
                        }
                    } while (match(COMMA));
                }
                consume(close, "expected annotation terminator");
            }
            List<Ast.TypeRef> canonicalArgs = canonicalizeNamedParameters(args);
            if (!seen.add(name)) {
                throw error(previous(), "duplicate @" + name
                        + " annotation; repeatable annotations require a registered schema");
            }
            result.add(new Ast.Annotation(name, canonicalArgs));
        }
        return result;
    }

    private Modifiers parseModifiers() {
        Ast.Visibility visibility = Ast.Visibility.PRIVATE;
        boolean async = false;
        boolean suspends = false;
        boolean generator = false;
        boolean generatorStarred = false;
        boolean structural = false;
        boolean nonLexical = false;
        boolean trapped = false;
        boolean isStatic = false;
        boolean isAbstract = false;
        boolean shared = false;
        boolean untrusted = false;
        boolean visibilitySeen = false;
        boolean asyncSeen = false;
        boolean suspendsSeen = false;
        boolean generatorSeen = false;
        boolean structuralSeen = false;
        boolean nonLexicalSeen = false;
        boolean trapSeen = false;
        boolean staticSeen = false;
        boolean abstractSeen = false;
        boolean sharedSeen = false;
        boolean untrustedSeen = false;

        while (true) {
            // The target critical-fnc contract is intentionally NOT enabled
            // by this parser: no typed guard owner, FIFO admission or transitive
            // no-suspend proof is wired for arbitrary methods. Reject the
            // modifier explicitly rather than allowing a misleading
            // fall-through parse error or a silently unsynchronized function.
            // A method literally named critical(...) is left to the normal
            // callable-name path during migration.
            if (isContextualIdentifier("critical") && !checkNext(LPAREN)) {
                if (suspends || checkNext(SUSPENDS)) {
                    throw error(peek(), "E_CALLABLE_MODIFIERS: critical suspends callable is forbidden");
                }
                throw error(peek(), "E_CRITICAL_UNSUPPORTED: critical fnc requires an authenticated owned guard and verified no-suspend effects");
            }
            // These spellings are recognized but deliberately unavailable
            // until typed RW admission / actor pipeline source lowering is
            // implemented. Never admit them as ordinary method names or
            // silently degrade the read mode to an exclusive write gate.
            if (isAdjacentCompoundKeyword("critical", "read")
                    || isAdjacentCompoundKeyword("critical", "write")) {
                throw error(peek(), "reader/writer critical declaration requires "
                        + "checked protected-state effects and scheduler-owned RW permits");
            }
            if (isAdjacentCompoundKeyword("transform", "pipe")) {
                throw error(peek(), "transform-pipe actor requires "
                        + "typed pipeline role, forwarding and transport lowering");
            }
            if (match(PUB)) {
                // A package-scoped member cannot be represented by today's
                // two-valued PRIVATE/PUBLIC AST. Never erase [pkg] and widen
                // access across tenant or package boundaries.
                if (check(LBRACKET)) {
                    throw error(peek(), "E_PKG_VISIBILITY_UNSUPPORTED: pub[pkg] requires "
                            + "an authenticated package scope and non-widening compiler lowering");
                }
                if (visibilitySeen) throw error(previous(), "duplicate/conflicting visibility modifier");
                visibilitySeen = true;
                visibility = Ast.Visibility.PUBLIC;
            } else if (match(PRIVATE)) {
                if (visibilitySeen) throw error(previous(), "duplicate/conflicting visibility modifier");
                visibilitySeen = true;
                visibility = Ast.Visibility.PRIVATE;
            } else if (match(ASYNC)) {
                if (asyncSeen) throw error(previous(), "duplicate 'async' modifier");
                asyncSeen = true;
                async = true;
            } else if (match(SUSPENDS) || matchContextualSuspendModifier()) {
                if (suspendsSeen) throw error(previous(), "duplicate 'suspends' modifier");
                suspendsSeen = true;
                suspends = true;
            } else if (match(GENERATOR)) {
                if (generatorSeen) throw error(previous(), "duplicate 'generator' modifier");
                generatorSeen = true;
                generator = true;
                // Plain 'generator' remains a legacy modifier only when a
                // callable kind follows. Bare declarations require a starred
                // marker so 'generator values()' cannot become accidental syntax.
                generatorStarred = match(STAR);
            } else if (check(IDENT) && peek().lexeme().equals("gen") && checkNext(STAR)) {
                Token gen = advance();
                advance(); // '*'
                if (generatorSeen) throw error(gen, "duplicate generator modifier");
                generatorSeen = true;
                generator = true;
                generatorStarred = true;
            } else if (matchContextualStructuralModifier()) {
                if (structuralSeen) throw error(previous(), "duplicate 'structural' modifier");
                structuralSeen = true;
                structural = true;
            } else if (match(NLEX)) {
                if (nonLexicalSeen) throw error(previous(), "duplicate 'nlex' modifier");
                nonLexicalSeen = true;
                nonLexical = true;
            } else if (match(TRAP)) {
                if (trapSeen) throw error(previous(), "duplicate 'trap' modifier");
                trapSeen = true;
                trapped = true;
            } else if (match(STATIC)) {
                if (staticSeen) throw error(previous(), "duplicate 'static' modifier");
                staticSeen = true;
                isStatic = true;
            } else if (match(ABSTRACT)) {
                if (abstractSeen) throw error(previous(), "duplicate 'abstract' modifier");
                abstractSeen = true;
                isAbstract = true;
            } else if (matchContextualShared()) {
                if (sharedSeen) throw error(previous(), "duplicate 'shared' modifier");
                sharedSeen = true;
                shared = true;
            } else if (match(UNTRUSTED)) {
                if (untrustedSeen) throw error(previous(), "duplicate 'untrusted' modifier");
                untrustedSeen = true;
                untrusted = true;
            } else {
                break;
            }
        }
        if (async && suspends) throw error(previous(), "'async suspends' is redundant; choose one");
        return new Modifiers(visibility, visibilitySeen, async, suspends, generator, generatorStarred, structural, nonLexical, trapped, isStatic, isAbstract, shared, untrusted);
    }

    private Modifiers mergeModifiers(Modifiers before, Modifiers after) {
        if (before.visibilitySeen && after.visibilitySeen) {
            throw error(previous(), "duplicate/conflicting visibility modifier");
        }
        if (before.async && after.async) throw error(previous(), "duplicate 'async' modifier");
        if (before.suspends && after.suspends) throw error(previous(), "duplicate 'suspends' modifier");
        if (before.async && after.suspends || before.suspends && after.async)
            throw error(previous(), "'async suspends' is redundant; choose one");
        if (before.generator && after.generator) throw error(previous(), "duplicate 'generator' modifier");
        if (before.structural && after.structural) throw error(previous(), "duplicate 'structural' modifier");
        if (before.nonLexical && after.nonLexical) throw error(previous(), "duplicate 'nlex' modifier");
        if (before.trapped && after.trapped) throw error(previous(), "duplicate 'trap' modifier");
        if (before.isStatic && after.isStatic) throw error(previous(), "duplicate 'static' modifier");
        if (before.isAbstract && after.isAbstract) throw error(previous(), "duplicate 'abstract' modifier");
        if (before.shared && after.shared) throw error(previous(), "duplicate 'shared' modifier");
        if (before.untrusted && after.untrusted) throw error(previous(), "duplicate 'untrusted' modifier");

        return new Modifiers(
                after.visibilitySeen ? after.visibility : before.visibility,
                before.visibilitySeen || after.visibilitySeen,
                before.async || after.async,
                before.suspends || after.suspends,
                before.generator || after.generator,
                before.generatorStarred || after.generatorStarred,
                before.structural || after.structural,
                before.nonLexical || after.nonLexical,
                before.trapped || after.trapped,
                before.isStatic || after.isStatic,
                before.isAbstract || after.isAbstract,
                before.shared || after.shared,
                before.untrusted || after.untrusted);
    }

    /**
     * Parse signature-only checked exceptional effects. These names remain
     * contextual so source-level throw/raise/panic keywords cannot be
     * misinterpreted as executable statements.
     */
    private Ast.EffectSpec parseCheckedEffects() {
        List<Ast.TypeRef> thrown = List.of();
        List<Ast.TypeRef> raised = List.of();
        List<Ast.TypeRef> panicked = List.of();
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (isContextualIdentifier("throws")
                || isContextualIdentifier("raises")
                || isContextualIdentifier("panics")) {
            String category = advance().lexeme();
            if (!seen.add(category)) {
                throw error(previous(), "duplicate checked " + category + " clause");
            }
            List<Ast.TypeRef> types = new ArrayList<>();
            if (check(LBRACE) || check(SEMICOLON) || check(END)
                    || check(EOF) || check(RBRACE)
                    || isContextualIdentifier("throws")
                    || isContextualIdentifier("raises")
                    || isContextualIdentifier("panics")) {
                throw error(peek(), "checked " + category + " requires a type");
            }
            types.add(parseTypeRef());
            // A top-level comma before the next contextual kind separates
            // *kinds*, whereas a comma before a type extends this kind's
            // nominal error-type list. The balanced type parser consumes
            // commas inside generic arguments itself.
            while (check(COMMA)) {
                if (current + 1 < tokens.size()
                        && tokens.get(current + 1).type() == IDENT
                        && java.util.Set.of("throws", "raises", "panics")
                                .contains(tokens.get(current + 1).lexeme())) {
                    advance(); // clause separator, not a new error type
                    break;
                }
                advance();
                types.add(parseTypeRef());
            }
            if (new java.util.HashSet<>(types).size() != types.size()) {
                throw error(previous(), "duplicate checked " + category + " type");
            }
            switch (category) {
                case "throws" -> thrown = List.copyOf(types);
                case "raises" -> raised = List.copyOf(types);
                case "panics" -> panicked = List.copyOf(types);
                default -> throw new IllegalStateException("unknown checked effect");
            }
        }
        return new Ast.EffectSpec(thrown, raised, panicked);
    }

    /** Canonical AttoLang never turns omitted named-callable results into void. */
    private Ast.TypeRef omittedNamedCallableReturn() {
        if (canonicalAttoSource) {
            throw error(peek(), "E_RETURN_ANNOTATION: named callable requires "
                    + "an explicit result (: T, -> T, : void, -> void or infer)");
        }
        return Ast.TypeRef.simple("void");
    }

    private Ast.TypeRef parseReturnType(List<Ast.Annotation> annotations) {
        Ast.TypeRef annotated = null;
        for (Ast.Annotation annotation : annotations) {
            if (annotation.name().equals("Ret")) {
                if (annotated != null) throw error(previous(), "duplicate @Ret return annotation");
                if (annotation.arguments().size() != 1) throw error(previous(), "@Ret requires exactly one type");
                annotated = annotation.arguments().getFirst();
            }
        }

        if (check(FAT_ARROW)) {
            throw error(peek(),
                    "fat arrow '=>' is reserved for function types/interface callable signatures; "
                            + "named executable callables use ': ReturnType' or '-> ReturnType'");
        }

        Ast.TypeRef declared = null;
        if (match(COLON) || match(ARROW)) {
            declared = parseTypeRef();
        }
        if (annotated != null && declared != null && !sameType(annotated, declared)) {
            throw error(previous(), "@Ret type and declared return type disagree");
        }
        return declared != null ? declared : annotated != null ? annotated : omittedNamedCallableReturn();
    }

    // A pipe after a name-first type may be a union separator or the
    // parameter-list terminator. The latter is followed by the slim arrow.
    // Preserve unrestricted unions in ordinary (...) parameter declarations.
    private Ast.TypeRef parsePipeParameterType() {
        Ast.TypeRef first = parsePostfixTypeAtom();
        if (!check(PIPE) || checkNext(ARROW)) return first;

        List<Ast.TypeRef> options = new ArrayList<>();
        options.add(first);
        do {
            consume(PIPE, "expected '|' between union parameter types");
            options.add(parsePostfixTypeAtom());
        } while (check(PIPE) && !checkNext(ARROW));
        return Ast.TypeRef.union(options);
    }

    private List<Ast.Param> parseDeclaredPipeParameters() {
        if (check(PIPE)) return List.of();
        List<Ast.Param> params = new ArrayList<>();
        do {
            boolean structural = matchContextualKeyword("structural") || match(TILDE);

            // Migration spelling for named-argument-friendly declarations:
            // |value: Type| is accepted, while oreslang-format emits |Type value|.
            if (!structural && check(IDENT) && checkNext(COLON)) {
                String name = advance().lexeme();
                consume(COLON, "expected ':' after compatibility parameter name");
                Ast.TypeRef type = parsePipeParameterType();
                boolean mutable = match(MUT);
                params.add(new Ast.Param(type, name, false, mutable));
                continue;
            }

            Ast.TypeRef type = parsePipeParameterType();
            boolean mutable = match(MUT);
            String name = consume(IDENT, "lambda-style callable declaration parameters require canonical 'Type name'").lexeme();
            if (structural && mutable) {
                throw error(previous(), "structural parameters are read-only borrowed views and cannot be 'mut'");
            }
            params.add(new Ast.Param(type, name, structural, mutable));
        } while (match(COMMA));
        requireDistinctParameterNames(params);
        return List.copyOf(params);
    }

    private boolean matchContextualKeyword(String keyword) {
        if (!check(IDENT) || !peek().lexeme().equals(keyword)) return false;
        advance();
        return true;
    }

    private boolean sameType(Ast.TypeRef a, Ast.TypeRef b) {
        return a.name().equals(b.name()) && a.arguments().equals(b.arguments()) && a.inferArguments() == b.inferArguments();
    }

    private List<Ast.GenericParameter> parseGenericParameterSpecs() {
        if (!match(LT)) return List.of();
        List<Ast.GenericParameter> specs = new ArrayList<>();
        java.util.Set<String> declared = new java.util.HashSet<>();
        do {
            String name = consume(IDENT, "expected generic parameter name").lexeme();
            if (name.equals("_") || !declared.add(name)) {
                throw error(previous(), "invalid or duplicate generic parameter '" + name + "'");
            }
            Ast.TypeRef defaultType = match(EQUAL) ? parseTypeRef() : null;
            specs.add(new Ast.GenericParameter(name, defaultType));
        } while (match(COMMA));
        consume(GT, "expected '>' after generic parameters");
        return List.copyOf(specs);
    }

    private List<String> parseGenericParameters() {
        if (!match(LT)) return List.of();
        List<String> names = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        do {
            String name = consume(IDENT, "expected generic parameter name").lexeme();
            // All declaration families must reserve inference-only '_' consistently.
            // Functions already enforce this through parseGenericParameterSpecs.
            if (name.equals("_") || name.startsWith("$")) {
                throw error(previous(), "invalid generic parameter name '" + name + "'");
            }
            if ("_".equals(name)) {
                throw error(previous(), "'_' is a generic argument inference hole, not a declaration parameter name");
            }
            if (!seen.add(name)) {
                throw error(previous(), "duplicate generic parameter name '" + name + "'");
            }
            names.add(name);
        } while (match(COMMA));
        consume(GT, "expected '>' after generic parameters");
        return List.copyOf(names);
    }

    private void requireDistinctParameterNames(List<Ast.Param> params) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Ast.Param param : params) {
            if (!names.add(param.name())) {
                throw error(previous(), "duplicate parameter name '" + param.name() + "'");
            }
        }
    }

    private List<Ast.Param> parseParametersUntil(Token.Type terminator) {
        return parseParametersUntil(terminator, java.util.Set.of());
    }

    private List<Ast.Param> parseParametersUntil(Token.Type terminator, java.util.Set<String> annotationStructuralNames) {
        if (check(terminator)) return List.of();
        List<Ast.Param> params = new ArrayList<>();
        do {
            // The parameter-specific structural sigil must not bypass
            // the retired lowercase collection type alias rejection.
            if (check(TILDE) && current + 2 < tokens.size()
                    && tokens.get(current + 1).type() == IDENT) {
                String kind = tokens.get(current + 1).lexeme();
                Token.Type suffix = tokens.get(current + 2).type();
                if ((suffix == LT || suffix == LBRACKET || suffix == LPAREN)
                        && switch (kind) {
                            case "array", "tuple", "vector", "fixed_array", "fixed_list",
                                    "list", "slice", "set", "map", "entry", "pair", "record" -> true;
                            default -> false;
                        }) {
                    throw error(peek(), "removed collection alias; '~' denotes structural typing");
                }
            }
            boolean structural = matchContextualKeyword("structural") || match(TILDE);

            // Canonical type-first structural spelling: structural Foo y
            if (structural) {
                Ast.TypeRef type = parseTypeRef();
                boolean mutable = match(MUT);
                String name = consume(IDENT, "expected parameter name").lexeme();
                if (mutable) {
                    throw error(previous(), "structural parameters are read-only borrowed views and cannot be 'mut'");
                }
                params.add(new Ast.Param(type, name, true, false));
                continue;
            }

            // Compatibility name-first structural spelling: y structural Foo
            if (check(IDENT) && checkNextLexeme("structural")) {
                String name = advance().lexeme();
                Token marker = consume(IDENT, "expected structural");
                if (!marker.lexeme().equals("structural")) throw error(marker, "expected structural");
                Ast.TypeRef type = parseTypeRef();
                boolean mutable = match(MUT);
                if (mutable) {
                    throw error(previous(), "structural parameters are read-only borrowed views and cannot be 'mut'");
                }
                params.add(new Ast.Param(type, name, true, false));
                continue;
            }

            // Function annotation spelling: @AllowStructural(y) fnc x(y Foo)
            if (check(IDENT) && annotationStructuralNames.contains(peek().lexeme()) && checkNext(IDENT)) {
                String name = advance().lexeme();
                Ast.TypeRef type = parseTypeRef();
                boolean mutable = match(MUT);
                if (mutable) {
                    throw error(previous(), "structural parameters are read-only borrowed views and cannot be 'mut'");
                }
                params.add(new Ast.Param(type, name, true, false));
                continue;
            }

            // Existing type-first compatibility spelling: @Structural Foo y
            if (match(AT)) {
                String annotation = consume(IDENT, "expected parameter annotation").lexeme();
                if (!annotation.equals("Structural")) throw error(previous(), "only @Structural is currently supported on parameters");
                structural = true;
            }

            // Temporary compatibility spelling: name: Type. Canonical source
            // remains Type name so ':' stays available for named call arguments.
            if (!structural && check(IDENT) && checkNext(COLON)) {
                String name = advance().lexeme();
                consume(COLON, "expected ':' after compatibility parameter name");
                Ast.TypeRef type = parseTypeRef();
                boolean mutable = match(MUT);
                params.add(new Ast.Param(type, name, false, mutable));
                continue;
            }

            Ast.TypeRef type = parseTypeRef();
            boolean mutable = match(MUT);
            String name = consume(IDENT, "expected parameter name after type").lexeme();
            if (structural && mutable) {
                throw error(previous(), "structural parameters are read-only borrowed views and cannot be 'mut'");
            }
            params.add(new Ast.Param(type, name, structural, mutable));
        } while (match(COMMA));
        requireDistinctParameterNames(params);
        return params;
    }

    private java.util.Set<String> structuralAnnotationNames(List<Ast.Annotation> annotations) {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (Ast.Annotation annotation : annotations) {
            if (!annotation.name().equals("AllowStructural")) continue;
            for (Ast.TypeRef argument : annotation.arguments()) {
                if (!argument.arguments().isEmpty() || argument.inferArguments() || argument.isStringLiteral()) {
                    throw error(previous(), "@AllowStructural arguments must be parameter names");
                }
                allowed.add(argument.name());
            }
        }
        return java.util.Set.copyOf(allowed);
    }

    private List<Ast.Param> applyStructuralAnnotations(List<Ast.Param> params, List<Ast.Annotation> annotations) {
        java.util.Set<String> allowed = new java.util.HashSet<>(structuralAnnotationNames(annotations));
        if (allowed.isEmpty()) return params;
        java.util.Set<String> found = new java.util.HashSet<>();
        List<Ast.Param> result = new ArrayList<>(params.size());
        for (Ast.Param param : params) {
            boolean structural = param.structural() || allowed.contains(param.name());
            if (structural && param.mutable()) {
                throw error(previous(), "structural parameters are read-only borrowed views and cannot be 'mut'");
            }
            if (allowed.contains(param.name())) found.add(param.name());
            result.add(new Ast.Param(param.type(), param.name(), structural, param.mutable()));
        }
        if (!found.equals(allowed)) {
            java.util.Set<String> missing = new java.util.HashSet<>(allowed);
            missing.removeAll(found);
            throw error(previous(), "@AllowStructural names unknown parameter(s): " + missing);
        }
        return List.copyOf(result);
    }

    /** Postfix type '?' is Option<T> sugar and binds more tightly than union '|'. */
    private Ast.TypeRef parseTypeRef() {
        Ast.TypeRef first = parsePostfixTypeAtom();
        if (!match(PIPE)) return first;

        List<Ast.TypeRef> options = new ArrayList<>();
        options.add(first);
        do options.add(parsePostfixTypeAtom()); while (match(PIPE));
        return Ast.TypeRef.union(options);
    }

    /** Group a union explicitly before '?' to wrap the complete union. */
    private Ast.TypeRef parsePostfixTypeAtom() {
        Ast.TypeRef type = parseTypeAtom();
        if (!match(QUESTION)) return type;
        if (type.name().equals("void") || type.name().equals("null")
                || type.inferArguments()) {
            throw error(previous(), "type-level '?' requires a concrete value type");
        }
        type = new Ast.TypeRef("Option", List.of(type), false);
        if (check(QUESTION)) {
            throw error(peek(), "a type may have only one postfix '?' suffix; use Option<Option<T>> for nested Option");
        }
        return type;
    }

    private Ast.TypeRef parseTypeAtom() {
        if (match(BANG)) return Ast.TypeRef.complement(parseTypeAtom());
        if (match(TRUE)) return Ast.TypeRef.boolLiteral(true);
        if (match(FALSE)) return Ast.TypeRef.boolLiteral(false);
        if ((check(STRUCT) || isContextualIdentifier("record"))
                && checkNext(LBRACE)) {
            boolean struct = check(STRUCT);
            advance();
            return parseClosedAggregateType(struct);
        }
        if (match(TYPE)) {
            Ast.TypeRef marked = parseTypeAtom();
            if (marked.name().startsWith("$")) {
                throw error(previous(), "'type' alias marker must prefix a named type");
            }
            return marked;
        }

        if (match(AMP)) {
            throw error(previous(),
                    "Oreslang does not use '&' for borrowed/reference types (no pointer syntax); use 'borrow T' or 'borrow mut T'");
        }
        if (matchContextualKeyword("borrow")) {
            boolean mutable = match(MUT);
            return Ast.TypeRef.borrowed(parseTypeAtom(), mutable);
        }
        if (match(AMP)) {
            throw error(previous(),
                    "borrowed types use 'borrow T' or 'borrow mut T'; pointer sigils are not Oreslang source syntax");
        }
        if (match(STRING)) return Ast.TypeRef.stringLiteral(previous().lexeme());

        if (match(LBRACKET)) {
            List<Ast.TypeRef> elements = new ArrayList<>();
            if (!check(RBRACKET)) {
                do elements.add(parseTypeRef()); while (match(COMMA));
            }
            consume(RBRACKET, "expected ']' after finite tuple type");
            return Ast.TypeRef.tupleType(elements);
        }

        if (match(LBRACE)) {
            java.util.LinkedHashMap<String, Ast.TypeRef> members = new java.util.LinkedHashMap<>();
            if (!check(RBRACE)) {
                do {
                    String field = consumeStaticObjectKeyName("expected record type field name");
                    consume(COLON, "expected ':' after record type field name");
                    Ast.TypeRef fieldType = parseTypeRef();
                    if (members.putIfAbsent(field, fieldType) != null) {
                        throw error(previous(), "duplicate record type field '" + field + "'");
                    }
                } while (match(COMMA));
            }
            consume(RBRACE, "expected '}' after record type");
            return Ast.TypeRef.recordType(members);
        }

        if (match(TYPEOF)) {
            if (isLegacyFnSpelling()) {
                throw error(peek(), "function types use 'typeof fnc(...) => ReturnType', not 'typeof fn(...)'");
            }
            // Existing callable-signature syntax has priority. A type query
            // is an independent static AST node, never a value expression.
            if (match(FNC)) return parseFunctionTypeSignature();
            if (check(BANG)) {
                throw error(peek(), "typeof! is not an ownership operator; use 'typeof name'");
            }
            if (match(LPAREN)) {
                Token binding = consume(IDENT, "typeof(...) expects exactly one binding name");
                consume(RPAREN, "typeof(...) expects exactly one binding name");
                return Ast.TypeRef.typeQuery(binding.lexeme());
            }
            return Ast.TypeRef.typeQuery(
                    consume(IDENT, "typeof requires a binding name").lexeme());
        }

        if (check(LPAREN) && looksLikeFunctionType()) return parseFunctionTypeSignature();
        if (match(LPAREN)) {
            Ast.TypeRef grouped = parseTypeRef();
            consume(RPAREN, "expected ')' after grouped type");
            return grouped;
        }

        // The tilde is reserved for structural typing, not the former
        // lowercase collection type aliases. Structural parameter syntax
        // consumes the sigil before entering parseTypeRef.
        if (match(TILDE)) {
            Token structuralSigil = previous();
            throw error(structuralSigil, "'~' marks structural typing, not a nominal collection alias; "
                    + "use 'structural T' in this position and canonical Type.of(...) for construction");
        }
        String name;
        if (match(VOID)) name = "void";
        else if (match(SELF)) name = "self";
        else if (match(NULL)) name = "null";
        else name = parseQualifiedName();

        List<Ast.TypeRef> args = new ArrayList<>();
        boolean infer = false;
        if ((name.equals("Pointer") || name.equals("RawPointer")) && check(LT)) {
            throw error(peek(),
                    name + "<T> is not Oreslang source syntax; use explicit rt copy/share/take/borrow operations");
        }
        // '<-' is a declaration marker, never the start of generic type
        // arguments. Keep LT+MINUS as separate lexer tokens to preserve
        // the old expression meaning of a<-b (a < -b).
        // Likewise '<<' is the channel read of `val name: T << source;`: a
        // type argument list never starts with '<'.
        if (!isAdjacentReturnFirstArrow() && !checkAdjacentPair(LT) && match(LT)) {
            if (match(GT)) {
                infer = true;
            } else if (name.equals("Fnc") || name.equals("Function")) {
                /*
                 * Callable types support both:
                 *   Fnc<Arg1, Arg2, Result>       (legacy comma form)
                 *   Fnc<Result(Arg1, Arg2)>       (signature-in-generics form)
                 *
                 * The signature form is normalized immediately to the same
                 * TypeRef representation used by "(Args...) => Result".
                 */
                Ast.TypeRef first = parseTypeRef();
                if (match(LPAREN)) {
                    List<Ast.TypeRef> params = parseFunctionTypeParametersAfterOpen();
                    consume(GT, "expected '>' after callable signature type");
                    return Ast.TypeRef.functionType(params, first);
                }

                args.add(first);
                while (match(COMMA)) args.add(parseTypeRef());
                consume(GT, "expected '>' after type arguments");
            } else {
                boolean seenNamedTypeArgument = false;
                do {
                    if (check(IDENT) && checkNext(EQUAL)) {
                        String parameterName = advance().lexeme();
                        consume(EQUAL, "expected '=' after named type parameter");
                        seenNamedTypeArgument = true;
                        args.add(Ast.TypeRef.namedParameter(parameterName, parseMetaValue()));
                    } else {
                        if (seenNamedTypeArgument) {
                            throw error(peek(),
                                    "positional type argument cannot follow a named type argument");
                        }
                        args.add(parseTypeRef());
                    }
                } while (match(COMMA));
                consume(GT, "expected '>' after type arguments / named parameters");
            }
        }

        // Newable[T] is a type-level static factory trait application,
        // not an array/collection sequence shape. Other bracketed type
        // syntax retains the existing sequence-shape interpretation.
        if (name.equals("Newable") && args.isEmpty() && match(LBRACKET)) {
            if (check(RBRACKET)) throw error(peek(), "Newable[T] requires a type argument");
            args.add(parseTypeRef());
            if (match(COMMA)) {
                throw error(previous(), "Newable[T] accepts exactly one type argument");
            }
            consume(RBRACKET, "expected ']' after Newable[T]");
        }
        args = canonicalizeNamedParameters(args);
        while (match(LBRACKET)) {
            // Zero-arity Tuple[] is a named tuple type.
            // Model the empty intrinsic with the existing finite tuple type
            // rather than allowing an empty sequence shape for every collection.
            if (name.equals("Tuple") && !infer && args.isEmpty() && check(RBRACKET)) {
                advance();
                return Ast.TypeRef.tupleType(List.of());
            }
            args.add(parseSequenceShapeDimension());
            consume(RBRACKET, "expected ']' after sequence type shape");
        }

        return new Ast.TypeRef(name, args, infer);
    }

    private Ast.TypeRef parseMetaValue() {
        if (match(INT)) return Ast.TypeRef.intLiteral(previous().lexeme());
        if (match(STRING)) return Ast.TypeRef.stringLiteral(previous().lexeme());
        if (match(TRUE)) return Ast.TypeRef.boolLiteral(true);
        if (match(FALSE)) return Ast.TypeRef.boolLiteral(false);
        return parseTypeRef();
    }

    private Ast.TypeRef parseSequenceShapeDimension() {
        if (check(RBRACKET)) throw error(peek(), "sequence type shape cannot be empty");
        List<Ast.TypeRef> patterns = new ArrayList<>();
        do patterns.add(parseSequenceTypePattern()); while (match(COMMA));
        return Ast.TypeRef.sequenceShape(patterns);
    }

    private Ast.TypeRef parseSequenceTypePattern() {
        if (match(ELLIPSIS)) {
            if (match(LPAREN)) {
                if (check(RPAREN)) throw error(peek(), "repeating sequence group cannot be empty");
                List<Ast.TypeRef> group = new ArrayList<>();
                do group.add(parseSequenceTypePattern()); while (match(COMMA));
                consume(RPAREN, "expected ')' after repeating sequence group");
                return Ast.TypeRef.repeatMany(group);
            }
            return Ast.TypeRef.repeatMany(List.of(parseSequenceTypePattern()));
        }
        if (check(INT) && checkNext(OF)) {
            String count = advance().lexeme();
            consume(OF, "expected 'of' in counted sequence pattern");
            return Ast.TypeRef.repeatExact(count, parseTypeRef());
        }
        return parseTypeRef();
    }

    private List<Ast.TypeRef> canonicalizeNamedParameters(List<Ast.TypeRef> args) {
        List<Ast.TypeRef> positional = new ArrayList<>();
        java.util.TreeMap<String, Ast.TypeRef> named = new java.util.TreeMap<>();
        List<Ast.TypeRef> shapes = new ArrayList<>();
        for (Ast.TypeRef arg : args) {
            if (arg.isNamedParameter()) {
                String key = arg.namedParameterName();
                if (named.putIfAbsent(key, arg) != null) {
                    throw error(previous(), "duplicate named type parameter '" + key + "'");
                }
            } else if (arg.isSequenceShape()) shapes.add(arg);
            else positional.add(arg);
        }
        positional.addAll(named.values());
        positional.addAll(shapes);
        return positional;
    }

    /**
     * Distinguish 'const name<(Args) => Result> = expression' from a
     * conventional type-first binding such as 'const Option<int> x = ...'.
     * Commit only after the entire signature and '=' have been recognized.
     */
    private boolean looksLikeNamedCallableBindingSignature() {
        if (!check(IDENT) || !checkNext(LT)
                || current + 2 >= tokens.size()
                || tokens.get(current + 2).type() != LPAREN) {
            return false;
        }
        int mark = current;
        try {
            advance(); // candidate binding name
            parseNamedCallableBindingSignature();
            return check(EQUAL);
        } catch (IllegalArgumentException malformed) {
            return false;
        } finally {
            current = mark;
        }
    }

    /**
     * Named binding signatures are type ascriptions, not generic declaration
     * parameters. 'const callback<(int, int) => int> = ...' is equivalent to
     * a typed Fnc<int, int, int> binding. The RHS must be compatible with this
     * exact callback contract, including its return type.
     */
    private Ast.TypeRef parseNamedCallableBindingSignature() {
        consume(LT, "named callable binding requires '<'");
        if (!check(LPAREN)) {
            throw error(peek(), "named callable binding requires '(Args...) => ReturnType'");
        }
        Ast.TypeRef signature = parseFunctionTypeSignature();
        consume(GT, "named callable binding requires '>' after its function signature");
        return signature;
    }

    private Ast.TypeRef parseFunctionTypeSignature() {
        consume(LPAREN, "expected '(' in function type");
        List<Ast.TypeRef> params = parseFunctionTypeParametersAfterOpen();
        consume(FAT_ARROW, "function types use the fat arrow '=>'");
        Ast.TypeRef result = parseTypeRef();
        return Ast.TypeRef.functionType(params, result);
    }

    private List<Ast.TypeRef> parseFunctionTypeParametersAfterOpen() {
        List<Ast.TypeRef> params = new ArrayList<>();
        if (!check(RPAREN)) {
            do {
                Ast.TypeRef paramType = parseTypeRef();
                if (check(IDENT)) advance(); // optional documentation-only parameter name
                params.add(paramType);
            } while (match(COMMA));
        }
        consume(RPAREN, "expected ')' after function type parameters");
        return params;
    }

    private boolean looksLikeFunctionType() {
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) depth++;
            else if (type == RPAREN) {
                depth--;
                if (depth == 0) return i + 1 < tokens.size() && tokens.get(i + 1).type() == FAT_ARROW;
            }
        }
        return false;
    }

    private String parseQualifiedName() {
        StringBuilder name = new StringBuilder(consume(IDENT, "expected name").lexeme());
        while (match(DOT)) name.append('.').append(consume(IDENT, "expected name after '.'").lexeme());
        return name.toString();
    }

    private List<Ast.Stmt> parseBlock() {
        consume(LBRACE, "expected '{'");
        localTypeScopes.push(new java.util.HashSet<>());
        try {
            List<Ast.Stmt> body = new ArrayList<>();
            while (!check(RBRACE) && !check(EOF)) body.add(parseStatement());
            consume(RBRACE, "expected '}'");
            return body;
        } finally {
            localTypeScopes.pop();
        }
    }

    /** Distinguish a statically declared local aggregate from an arbitrary name
     *  followed by a control-flow block. Only prior lexical declarations qualify.
     */
    private boolean isLocalTypeName(String name) {
        for (java.util.Set<String> scope : localTypeScopes) {
            if (scope.contains(name)) return true;
        }
        return false;
    }

    private Ast.LocalTypeDeclStmt parseLocalTypeDeclaration(boolean nominal, boolean structShortForm) {
        String name = consume(IDENT, "expected local type name").lexeme();
        Ast.TypeRef target;
        if (structShortForm) {
            target = parseClosedAggregateType(nominal);
        } else {
            consume(EQUAL, "expected '=' after local type name");
            target = parseTypeRef();
        }
        if (localTypeScopes.isEmpty() || !localTypeScopes.peek().add(name)) {
            throw error(previous(), "duplicate local type declaration '" + name + "'");
        }
        match(SEMICOLON);
        return new Ast.LocalTypeDeclStmt(name, target, nominal);
    }

    private Ast.TypeRef parseClosedAggregateType(boolean struct) {
        consume(LBRACE, "expected '{' in aggregate type");
        java.util.Map<String, Ast.TypeRef> fields = new java.util.LinkedHashMap<>();
        while (!check(RBRACE) && !check(EOF)) {
            String field;
            Ast.TypeRef type;
            if (check(IDENT) && checkNext(COLON)) {
                field = consumeStaticObjectKeyName("expected aggregate field name");
                consume(COLON, "expected ':' after aggregate field name");
                type = parseTypeRef();
            } else {
                type = parseTypeRef();
                field = consumeStaticObjectKeyName("expected aggregate field after its type");
            }
            if (fields.putIfAbsent(field, type) != null) {
                throw error(previous(), "duplicate aggregate field '" + field + "'");
            }
            if (!match(COMMA, SEMICOLON)) break;
        }
        consume(RBRACE, "expected '}' after aggregate type");
        return struct ? Ast.TypeRef.structType(fields) : Ast.TypeRef.recordType(fields);
    }

    private Ast.LocalAggregateConstructExpr parseLocalAggregateConstructor(String name) {
        consume(LBRACE, "expected '{' in local aggregate constructor");
        List<Ast.Expr> positional = new ArrayList<>();
        List<Ast.ObjectField> named = new ArrayList<>();
        if (!check(RBRACE)) {
            do {
                if (check(IDENT) && checkNext(COLON)) {
                    String field = consumeStaticObjectKeyName("expected aggregate field name");
                    consume(COLON, "expected ':' after aggregate field name");
                    named.add(Ast.ObjectField.named(field, parseExpression()));
                } else {
                    positional.add(parseExpression());
                }
                if (!positional.isEmpty() && !named.isEmpty()) {
                    throw error(previous(), "cannot mix positional and named aggregate constructor fields");
                }
            } while (match(COMMA));
        }
        consume(RBRACE, "expected '}' after local aggregate constructor");
        return new Ast.LocalAggregateConstructExpr(name, positional, named);
    }

    /**
     * First executable AttoLang maybe slice. This is deliberately lowered to
     * the existing *procedural* checked match CFG; value-match arm-result
     * rewriting would turn an inner function return into the wrong control
     * transfer. Failure handlers are copied only to their compatible variant
     * (Err for Ok, None for Some); the existing checker proves exhaustiveness,
     * binding type and moves on each branch.
     *
     * V1 admits contiguous fallible bindings and a terminal body; independent
     * statements before/among bindings and body-local defer need dedicated
     * cleanup CFG lowering. Reject rather than silently change cleanup order.
     */
    private record MaybeBinding(Ast.Pattern success, Ast.Expr value, String failedVariant) { }

    private boolean isMaybeBindingAt(int index) {
        if (index + 2 >= tokens.size()
                || tokens.get(index).type() != IDENT
                || tokens.get(index + 1).type() != LPAREN) return false;
        String name = tokens.get(index).lexeme();
        if (!name.equals("Ok") && !name.equals("Some")) return false;
        int depth = 0;
        for (int i = index + 1; i < tokens.size(); i++) {
            Token.Type kind = tokens.get(i).type();
            if (kind == LPAREN) depth++;
            else if (kind == RPAREN && --depth == 0) {
                return i + 1 < tokens.size()
                        && tokens.get(i + 1).type() == FALLIBLE_BIND;
            }
        }
        return false;
    }

    private Ast.Stmt parseMaybe(boolean returning) {
        List<MaybeBinding> bindings = new ArrayList<>();
        while (isMaybeBindingAt(current)) {
            Ast.Pattern pattern = parsePattern();
            if (!(pattern instanceof Ast.ConstructorPattern constructor)
                    || constructor.arguments().size() != 1
                    || !(constructor.arguments().getFirst() instanceof Ast.BindingPattern
                        || constructor.arguments().getFirst() instanceof Ast.WildcardPattern)
                    || !(constructor.constructor().equals("Ok")
                        || constructor.constructor().equals("Some"))) {
                throw error(previous(), "maybe v1 requires Ok(name) or Some(name) fallible bindings");
            }
            consume(FALLIBLE_BIND, "maybe binding requires the atomic '?=' operator");
            Ast.Expr value = parseExpression();
            consume(SEMICOLON, "maybe fallible bindings must end with ';'");
            bindings.add(new MaybeBinding(pattern, value,
                    constructor.constructor().equals("Ok") ? "Err" : "None"));
        }
        if (bindings.isEmpty()) {
            throw error(peek(), "maybe requires at least one Ok(name) ?= or Some(name) ?= binding");
        }

        List<Ast.Stmt> normal = parseUntil(ELSE, END);
        if (normal.isEmpty()) {
            throw error(peek(), "maybe success path must explicitly return or produce a result");
        }
        rejectMaybeBodyDefer(normal);
        if (returning) normal = functionReturnMaybeTail(normal);
        consume(ELSE, "maybe v1 requires exhaustive 'else' failure arms");

        List<Ast.MatchArm> handlers = new ArrayList<>();
        boolean seenErr = false;
        boolean seenNone = false;
        boolean seenWildcard = false;
        while (!check(END) && !check(EOF)) {
            // Legacy match-arm examples may prefix variants with contextual
            // 'on'. Never allow a previous wildcard to swallow later arms.
            if (seenWildcard) {
                throw error(peek(), "maybe else wildcard '_' must be the final arm");
            }
            matchContextualIdentifier("on");
            Ast.Pattern pattern = parsePattern();
            if (pattern instanceof Ast.ConstructorPattern constructor) {
                if (constructor.constructor().equals("Err")) {
                    if (constructor.arguments().size() != 1) {
                        throw error(previous(), "maybe Err handler requires exactly one payload pattern");
                    }
                    if (seenErr) throw error(previous(), "duplicate maybe Err failure arm");
                    seenErr = true;
                } else if (constructor.constructor().equals("None")) {
                    if (!constructor.arguments().isEmpty()) {
                        throw error(previous(), "maybe None handler must not bind a payload");
                    }
                    if (seenNone) throw error(previous(), "duplicate maybe None failure arm");
                    seenNone = true;
                } else {
                    throw error(previous(), "maybe else requires Err(...), None, or _ failure pattern");
                }
            } else if (pattern instanceof Ast.WildcardPattern) {
                seenWildcard = true;
            } else {
                throw error(previous(), "maybe else requires Err(...), None, or _ failure pattern");
            }
            consume(ARROW, "maybe failure arms require '->'");
            boolean braced = check(LBRACE);
            List<Ast.Stmt> handler = braced ? parseBlock() : List.of(parseStatement());
            if (braced) match(SEMICOLON);
            if (handler.isEmpty()) {
                throw error(previous(), "maybe failure arm cannot be empty");
            }
            if (returning) handler = functionReturnMaybeTail(handler);
            handlers.add(new Ast.MatchArm(pattern, null, handler));
        }
        consume(END, "expected 'end' to close maybe");
        if (returning) {
            consume(SEMICOLON, "return maybe expression requires ';' after end");
        } else {
            match(SEMICOLON);
        }
        if (handlers.isEmpty()) {
            throw error(previous(), "maybe requires at least one failure arm");
        }

        List<Ast.Stmt> next = normal;
        for (int i = bindings.size() - 1; i >= 0; i--) {
            MaybeBinding binding = bindings.get(i);
            List<Ast.MatchArm> arms = new ArrayList<>();
            arms.add(new Ast.MatchArm(binding.success(), null, next));
            for (Ast.MatchArm handler : handlers) {
                if (maybeFailureVariant(handler.pattern(), binding.failedVariant())
                        || handler.pattern() instanceof Ast.WildcardPattern) {
                    arms.add(handler);
                }
            }
            if (arms.size() == 1) {
                throw error(previous(), "non-exhaustive maybe: missing "
                        + binding.failedVariant() + " failure arm");
            }
            // Procedural matching must propagate inner returns to the callable.
            next = List.of(new Ast.MatchStmt(binding.value(), true, arms, true));
        }
        return new Ast.BlockStmt(next);
    }

    private static boolean maybeFailureVariant(Ast.Pattern pattern, String variant) {
        return pattern instanceof Ast.ConstructorPattern constructor
                && constructor.constructor().equals(variant);
    }

    private List<Ast.Stmt> functionReturnMaybeTail(List<Ast.Stmt> body) {
        if (body.isEmpty()) throw error(previous(), "return maybe branch requires a value or return");
        Ast.Stmt last = body.getLast();
        if (last instanceof Ast.ReturnStmt) return body;
        if (!(last instanceof Ast.ExprStmt expression)) {
            throw error(previous(), "return maybe branch needs terminal value or explicit function return");
        }
        List<Ast.Stmt> normalized = new ArrayList<>(body);
        normalized.set(normalized.size() - 1, new Ast.ReturnStmt(expression.expression()));
        return List.copyOf(normalized);
    }

    private void rejectMaybeBodyDefer(List<Ast.Stmt> body) {
        // A shared function-scope defer is already correct. But an in-body
        // defer cannot be pushed into nested match success arms: on a later
        // failure, it must run BEFORE the else handler, not after it.
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.DeferStmt
                    || stmt instanceof Ast.BlockStmt
                    || stmt instanceof Ast.IfStmt
                    || stmt instanceof Ast.MatchStmt
                    || stmt instanceof Ast.TryStmt
                    || stmt instanceof Ast.LoopStmt
                    || stmt instanceof Ast.ForStmt
                    || stmt instanceof Ast.ForOfStmt
                    || stmt instanceof Ast.ForOfDestructureStmt
                    || stmt instanceof Ast.DoWhileStmt
                    || stmt instanceof Ast.SelectStmt
                    || stmt instanceof Ast.TaskScopeStmt
                    || stmt instanceof Ast.UsingBorrowStmt) {
                throw error(previous(), "maybe v1 body admits only expression statements "
                        + "and a terminal return; nested scopes/defer need typed cleanup CFG");
            }
        }
    }

    private Ast.Stmt parseStatement() {
        rejectUnsupportedAcceleratorSyntax();
        if (check(STOP) && (checkNext(IDENT) || checkNext(SELF) || checkNext(BANG))) {
            Token operator = advance();
            consumeActorOperatorBang(operator, "E_STOP_BANG_BOUNDARY");
            throw error(operator, "E_STOP_BACKEND_UNSUPPORTED: actor stop/stop! require "
                    + "checked graceful shutdown, lifecycle ownership and supervisor lowering");
        }
        // Contextual fallible-value scope: this is not an exception catch.
        // Keep local identifiers named maybe legal outside the two admitted forms.
        if (check(DO) && checkNext(IDENT)
                && tokens.get(current + 1).lexeme().equals("maybe")) {
            advance(); // do
            advance(); // maybe
            return parseMaybe(false);
        }
        if (attoCallableVersion != null && match(CARET)) {
            Ast.Expr value = check(SEMICOLON) || isSafeStatementBoundary()
                    ? null : parseExpression();
            consumeExpressionStatementTerminator(value, "caret return requires ';'");
            return new Ast.ReturnStmt(value);
        }
        // A contextual statement keyword: calls to a function named using(...)
        // retain their existing expression behavior. Scoped borrow syntax is
        // parsed distinctly so compiler admission cannot silently desugar it.
        if (check(IDENT) && peek().lexeme().equals("using")
                && (checkNext(IDENT) || checkNext(SELF))) {
            advance();
            // 'as' delimits the scoped alias here, rather than Oreslang's
            // ordinary checked-cast expression. Restore prior refinement
            // mode even if the resource expression is malformed.
            boolean previousRefinementMode = suppressRefinementOperators;
            Ast.Expr resource;
            try {
                suppressRefinementOperators = true;
                resource = parseExpression();
            } finally {
                suppressRefinementOperators = previousRefinementMode;
            }
            if (!(resource instanceof Ast.CallExpr call)
                    || !(call.callee() instanceof Ast.MemberExpr member)
                    || call.typeArgumentsPresent()) {
                throw error(previous(),
                        "using borrowed scope requires a zero-argument resource.borrow() call");
            }
            boolean telemetrySpan = member.member().equals("span")
                    && member.receiver() instanceof Ast.NameExpr namespace
                    && namespace.name().equals("telemetry");
            boolean borrowedView = member.member().equals("borrow")
                    && call.arguments().isEmpty();
            if (!telemetrySpan && !borrowedView) {
                throw error(previous(),
                        "using borrowed scope requires a zero-argument resource.borrow() call");
            }
            if (telemetrySpan && call.arguments().size() > 1) {
                throw error(previous(),
                        "using telemetry.span accepts zero or one span-name argument");
            }
            consume(AS, telemetrySpan
                    ? "using telemetry.span requires 'as <span>'"
                    : "using borrowed scope requires 'as <view>'");
            Token name = consume(IDENT, telemetrySpan
                    ? "using telemetry.span requires a binding name"
                    : "using borrowed scope requires a binding name");
            if (name.lexeme().equals("_") || name.lexeme().equals("using")) {
                throw error(name, telemetrySpan
                        ? "using telemetry.span needs a named, usable span binding"
                        : "using borrowed scope needs a named, usable view binding");
            }
            List<Ast.Stmt> body = parseBlock();
            if (telemetrySpan) {
                // Contextual intrinsic; bypass ordinary call evaluation so a
                // guest binding named telemetry cannot spoof runtime authority.
                Ast.Expr spanName = call.arguments().isEmpty()
                        ? new Ast.LiteralExpr("span")
                        : call.arguments().getFirst();
                return new Ast.UsingTelemetrySpanStmt(spanName, name.lexeme(), body);
            }
            return new Ast.UsingBorrowStmt(resource, name.lexeme(), body);
        }
        // Keep these distinct from ordinary calls/identifiers. Exception
        // effect checking, typed recovery and actor CONTROL delivery have
        // not yet been lowered in the Java/Graal production compiler.
        if (check(THROW) || check(RAISE) || check(PANIC) || check(RECOVER)) {
            throw error(peek(), "typed exceptional effect '" + peek().lexeme()
                    + "' requires checked exception and supervision lowering; source effect is not yet enabled");
        }
        // The value-producing static select expression is not lowered yet.
        // Preserve historical statement sources until expression migration
        // provides an executable replacement; do-select stays procedural.
        // Contextual, not reserved: this form is lowered to typed, procedural
        // Result/Option matches. Normal identifiers named maybe are unaffected.
        if (check(DO) && checkNext(IDENT)
                && tokens.get(current + 1).lexeme().equals("maybe")) {
            advance(); // do
            advance(); // maybe
            return parseMaybe(false);
        }
        if (looksLikeStaticSelectStatement()) return parseStaticSelectStatement();
        // "cb" is reserved for callback-bearing writech; it is not the
        // no-result/static-select marker. "do" is the canonical spelling.
        if (check(CB) && checkNext(SELECT)) {
            throw error(peek(), "use 'do select { ... }' or 'do select over ... end' instead of 'cb select'");
        }
        if (check(NB) && checkNext(CB)
                && current + 2 < tokens.size()
                && tokens.get(current + 2).type() == SELECT) {
            throw error(peek(), "use 'do nb select { ... }' or 'do nb select over ... end' instead of 'nb cb select'");
        }
        if (check(DO) && (checkNext(SELECT)
                || (checkNext(NB) && current + 2 < tokens.size()
                    && tokens.get(current + 2).type() == SELECT))) {
            throw error(peek(), "'do select' requires static cases using '{ ... }' or 'over ... end'; "
                    + "dynamic 'select from cases' produces a result and "
                    + "has no callback arms to handle discarded reads");
        }
        if (looksLikeImmediateChannelExpression()) {
            Ast.Expr expression = parseExpression();
            consumeStatementTerminator("channel probe expression should end with ';'");
            return new Ast.ExprStmt(expression);
        }
        // Runtime blocks may create values, never declaration identities. This
        // keeps source semantics compatible with both closed-world AOT and JIT:
        // JIT may specialize known declarations, but it cannot invent modules,
        // namespaces, classes, or trait/impl identities while executing code.
        if (check(NAMESPACE)) {
            throw error(peek(), "namespace declarations are file-scope compile-time declarations and cannot execute inside a block");
        }
        if (check(DEFINE)) {
            Token define = peek();
            Token.Type next = current + 1 < tokens.size() ? tokens.get(current + 1).type() : EOF;
            if (next == MODULE) {
                throw error(define, "module declarations are compile-time declarations and cannot execute inside a block");
            }
            if (next == CLASS) {
                throw error(define, "class declarations are compile-time declarations and cannot execute inside a block");
            }
            if (next == TRAIT) {
                throw error(define, "trait declarations are compile-time declarations and cannot execute inside a block");
            }
        }
        if (check(TRAIT)) {
            throw error(peek(), "trait declarations are static/module-scope declarations; runtime trait creation is forbidden");
        }
        if (check(CLASS)) {
            throw error(peek(), "class declarations are module-scope and cannot be declared inside a callable");
        }
        if (check(CONST) && checkNext(TYPE)) {
            throw error(peek(), "'const type' is redundant; types are compile-time immutable declarations");
        }
        if (check(STRUCT) && checkNext(IDENT)) {
            advance();
            return parseLocalTypeDeclaration(true, true);
        }
        if (isContextualIdentifier("record") && checkNext(IDENT)) {
            advance();
            return parseLocalTypeDeclaration(false, true);
        }
        if (match(TYPE)) {
            return parseLocalTypeDeclaration(false, false);
        }

        if (check(AT)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            if (!isBindingKind(peek().type())) {
                throw error(peek(), "local annotations currently apply only to typed bindings");
            }
            return parseBindingStatement(annotations);
        }
        if (isBindingKind(peek().type()) && looksLikePrefixedDestructure()) {
            Ast.BindingKind inherited = parseBindingKind();
            boolean inheritedMutableReferent = parseReferentMutability(inherited);
            return parseDestructure(
                    (check(LBRACKET) || check(LPAREN)) ? Ast.DestructureKind.SEQUENCE : Ast.DestructureKind.OBJECT,
                    inherited,
                    inheritedMutableReferent);
        }
        if (isBindingKind(peek().type())) return parseBindingStatement();
        // 'mut' modifies a referent capability only after an explicit
        // binding qualifier. It never implicitly means 'const mut'.
        if (check(MUT)) {
            throw error(peek(), "E_MUT_QUALIFIER: bare 'mut' needs explicit const, val or let");
        }
        if (looksLikePrimitiveTypeFirstImmutableBinding()) {
            return parsePrimitiveTypeFirstImmutableBinding();
        }
        if (check(LBRACKET) && looksLikeDestructure()) {
            return parseDestructure(Ast.DestructureKind.SEQUENCE, null, false);
        }
        if (check(LBRACE) && looksLikeDestructure()) {
            return parseDestructure(Ast.DestructureKind.OBJECT, null, false);
        }
        if (canonicalAttoSource && match(CARET)) {
            Ast.Expr value = match(VOID) || check(SEMICOLON) || isSafeStatementBoundary() ? null : parseExpression();
            consumeExpressionStatementTerminator(value, "return statement should end with ';'");
            return new Ast.ReturnStmt(value);
        }
        if (match(RETURN)) {
            // Contextual return-maybe: its inner return statements are true
            // FUNCTION_RETURN. It is NOT the value-match ARM_RESULT construct.
            // No synthetic outer ReturnStmt is emitted for diverging arms.
            if (isContextualIdentifier("maybe") && isMaybeBindingAt(current + 1)) {
                advance();
                return parseMaybe(true);
            }
            Ast.Expr value = (canonicalAttoSource && match(VOID)) || check(SEMICOLON) || isSafeStatementBoundary() ? null : parseExpression();
            consumeExpressionStatementTerminator(value, "return statement should end with ';'");
            return new Ast.ReturnStmt(value);
        }
        if (match(YIELD)) {
            boolean delegated = match(STAR);
            if (check(SEMICOLON) || isSafeStatementBoundary()) {
                throw error(previous(), delegated ? "yield* requires an iterable value" : "yield requires a value");
            }
            Ast.Expr value = parseExpression();
            consumeExpressionStatementTerminator(
                    value,
                    delegated
                            ? "yield* statement should end with ';'"
                            : "yield statement should end with ';'");
            return new Ast.YieldStmt(value, delegated);
        }
        if (match(DEFER)) {
            Ast.Expr expression = parseExpression();
            consumeExpressionStatementTerminator(expression, "defer statement should end with ';'");
            return new Ast.DeferStmt(expression);
        }
        if (check(BLOCK) && checkNext(LBRACE)) {
            advance();
            return new Ast.BlockStmt(parseBlock());
        }
        if (match(BREAK)) {
            consumeStatementTerminator("break statement should end with ';'");
            return new Ast.BreakStmt();
        }
        if (match(CONTINUE)) {
            consumeStatementTerminator("continue statement should end with ';'");
            return new Ast.ContinueStmt();
        }
        if (match(WHILE)) {
            // Route the while backedge through the existing source-task
            // ForStmt trampoline: one continuation and one budget mechanism.
            ConditionHead head = parseConditionHead(true);
            match(SEMICOLON);
            List<Ast.Stmt> body = parseLoopBody();
            if (head.binding() == null) {
                if (dev.oreslang.ast.ConditionCaptures.contains(head.predicate())
                        || head.predicate() instanceof Ast.PatternTestExpr
                        || head.predicate() instanceof Ast.BinaryExpr binary
                            && binary.left() instanceof Ast.PatternTestExpr) {
                    // Use an if's condition/branch scope afresh on every test.
                    return new Ast.ForStmt(null, new Ast.LiteralExpr(true), null,
                            List.of(new Ast.IfStmt(List.of(new Ast.IfBranch(
                                    head.predicate(), body)), List.of(new Ast.BreakStmt()))));
                }
                return new Ast.ForStmt(null, head.predicate(), null, body);
            }
            // Re-enter the binding scope on every test, including after continue.
            List<Ast.Stmt> turn = new ArrayList<>();
            turn.add(head.binding());
            turn.add(new Ast.IfStmt(List.of(new Ast.IfBranch(
                    new Ast.UnaryExpr("!", head.predicate()),
                    List.of(new Ast.BreakStmt()))), List.of()));
            turn.addAll(body);
            return new Ast.ForStmt(null, new Ast.LiteralExpr(true), null, turn);
        }
        if (match(GUARD)) return parseGuard();
        if (canonicalAttoSource && check(DO) && (checkNext(IF) || checkNext(IFNOT) || checkNext(UNLESS))) {
            advance();
            return parseIf(advance().type(), true);
        }
        if (match(IF, IFNOT, UNLESS)) return parseIf(previous().type(), false);
        if ((check(NB)
                || (check(IDENT) && peek().lexeme().equals("task")))
                && current + 2 < tokens.size()
                && tokens.get(current + 1).type() == IDENT
                && tokens.get(current + 1).lexeme().equals("scope")
                && (tokens.get(current + 2).type() == LBRACE
                    || (tokens.get(current + 2).type() == IDENT
                        && (tokens.get(current + 2).lexeme().equals("deadline")
                            || tokens.get(current + 2).lexeme().equals("supervise"))))) {
            advance(); // nb or contextual task
            advance(); // contextual scope
            Ast.Expr deadline = null;
            boolean supervise = false;
            while (check(IDENT)) {
                String option = peek().lexeme();
                if (option.equals("deadline") && deadline == null) {
                    advance();
                    consume(LPAREN, "expected '(' after task scope deadline");
                    deadline = parseExpression();
                    consume(RPAREN, "expected ')' after task scope deadline milliseconds");
                } else if (option.equals("supervise") && !supervise) {
                    advance();
                    supervise = true;
                } else {
                    throw error(peek(), "task scope accepts deadline(ms) and supervise, each at most once");
                }
            }
            Ast.Stmt scoped = new Ast.TaskScopeStmt(parseBlock(), deadline, supervise);
            match(SEMICOLON);
            return scoped;
        }
        if (check(IDENT) && peek().lexeme().equals("task")
                && current + 2 < tokens.size()
                && tokens.get(current + 1).type() == IDENT
                && tokens.get(current + 1).lexeme().equals("context")
                && tokens.get(current + 2).type() == LPAREN) {
            // task context(#key, value) { body }  desugars to
            // block { $task_context_enter(#key, value); defer $task_context_exit(); body }
            // so the binding is restored exactly once on every exit path,
            // including cancellation unwind. '$' names are not source-lexable.
            advance(); // task
            advance(); // context
            consume(LPAREN, "expected '(' after task context");
            Ast.Expr key = parseExpression();
            consume(COMMA, "task context expects (#key, value)");
            Ast.Expr value = parseExpression();
            consume(RPAREN, "expected ')' after task context binding");
            List<Ast.Stmt> body = new ArrayList<>();
            body.add(new Ast.ExprStmt(new Ast.CallExpr(
                    new Ast.NameExpr("$task_context_enter"), List.of(), false, List.of(key, value))));
            body.add(new Ast.DeferStmt(new Ast.CallExpr(
                    new Ast.NameExpr("$task_context_exit"), List.of(), false, List.of())));
            body.addAll(parseBlock());
            match(SEMICOLON);
            return new Ast.BlockStmt(body);
        }
        if (check(DO) && checkNext(LBRACE)) {
            advance();
            return parseDoWhile();
        }
        // Procedural dispatch returns from its enclosing callable. Keep this
        // distinction in the AST instead of relying on source spelling later.
        if (match(DO)) {
            if (match(MATCH)) return parseMatch(true);
            if (match(SWITCH)) return parseSwitch();
            // Explicitly discard a channel read result (or write's unit result)
            // without changing the operation's cooperative suspension mode.
            // An nb channel operation returns a Future and must not be silently
            // detached by 'do'; retain the handle or await it instead.
            if (match(READCH, WRITECH)) {
                Token operation = previous();
                Ast.Expr channelOp = parseChannelOperation(
                        operation.type() == READCH
                                ? Ast.ChannelOperation.READ
                                : Ast.ChannelOperation.WRITE,
                        Ast.WaitMode.BLOCKING);
                consumeExpressionStatementTerminator(
                        channelOp, "do channel operation should end with ';'");
                return new Ast.ExprStmt(channelOp);
            }
            if (check(NB) && (checkNext(READCH) || checkNext(WRITECH))) {
                throw error(peek(),
                        "do nb readch/writech would discard a pending Future; bind or await it");
            }
            throw error(previous(),
                    "'do' requires match, switch, readch, writech, or a braced select");
        }
        // Legacy statement syntax remains accepted during migration.
        if (match(MATCH)) {
            // Canonical bare match is a discarded value, not a callable exit.
            if (canonicalAttoSource) {
                Ast.Expr value = parseValueMatch();
                match(SEMICOLON);
                return new Ast.ExprStmt(value);
            }
            return parseMatch(false);
        }
        if (match(SWITCH)) return parseSwitch();
        if (match(TRY)) return parseTry();
        if (check(LOOP) && (checkNext(LBRACE) || checkNext(DO))) {
            advance();
            return new Ast.LoopStmt(parseLoopBody());
        }
        if (match(FOR)) {
            if (check(LBRACE) || check(DO)) {
                return new Ast.LoopStmt(parseLoopBody());
            }
            boolean asyncIteration = match(AWAIT);
            return parseFor(asyncIteration);
        }

        Ast.Expr expression = parseExpression();
        if (checkIoReadBinder()) return parseSourceFirstChannelRead(expression);
        if (expression instanceof Ast.BinaryExpr write && write.operator().equals("<<")) {
            // `sink << value;` is exactly `writech sink, value;`. A chained or
            // nested `<<` stays a BinaryExpr and the checker rejects it.
            expression = Ast.IoShorthand.write(write.left(), write.right());
        }
        consumeExpressionStatementTerminator(expression, "expression statement should end with ';'");
        return new Ast.ExprStmt(expression);
    }

    /** At {@code >> val|let|const}: a read that declares its binding (never a shift). */
    private boolean checkIoReadBinder() {
        return checkAdjacentPair(GT)
                && current + 2 < tokens.size()
                && !(tokens.get(current + 2).type() == GT && adjacent(tokens.get(current + 1), tokens.get(current + 2)))
                && isBindingKind(tokens.get(current + 2).type());
    }

    /** The token after this binding name starts {@code <<} (not {@code <<<}). */
    private boolean nameIsFollowedByIoDeclarationRead() {
        current++;
        try {
            return checkIoDeclarationRead();
        } finally {
            current--;
        }
    }

    /** At {@code <<} but not {@code <<<}: the declaration-first read {@code val name << source;}. */
    private boolean checkIoDeclarationRead() {
        return checkAdjacentPair(LT)
                && !(current + 2 < tokens.size() && tokens.get(current + 2).type() == LT
                        && adjacent(tokens.get(current + 1), tokens.get(current + 2)));
    }

    private IllegalArgumentException constChannelRead(Token at) {
        return error(at, "E_CONST_EFFECT: a runtime channel read cannot initialize 'const'"
                + " (const is compile-time); use 'val' or 'let'");
    }

    /**
     * {@code source >> val name;}, {@code source >> let name: T;} or
     * {@code source >> val T name;}: exactly {@code val name = readch source;}.
     */
    private Ast.Stmt parseSourceFirstChannelRead(Ast.Expr source) {
        advance();
        advance(); // >>
        if (check(CONST)) throw constChannelRead(peek());
        Ast.BindingKind kind = parseBindingKind();
        boolean mutableReferent = parseReferentMutability(kind);
        Ast.TypeRef type = null;
        String name;
        if (check(IDENT) && checkNext(COLON)) {
            name = advance().lexeme();
            consume(COLON, "expected ':' after read binding name");
            type = parseTypeRef();
        } else if (check(IDENT) && (checkNext(SEMICOLON) || current + 1 >= tokens.size()
                || tokens.get(current + 1).type() == RBRACE)) {
            name = advance().lexeme();
        } else {
            type = parseTypeRef();
            name = consume(IDENT, "expected read binding name after '>> val|let'").lexeme();
        }
        Ast.Expr read = Ast.IoShorthand.read(source);
        consumeExpressionStatementTerminator(read, "channel read should end with ';'");
        return new Ast.BindingStmt(kind, type, name, read, mutableReferent);
    }

    private boolean looksLikeImmediateChannelExpression() {
        if (!check(TRY) || current + 1 >= tokens.size()) return false;
        Token.Type next = tokens.get(current + 1).type();
        return next == READCH || next == WRITECH || next == SELECT;
    }

    private boolean looksLikeStaticSelectStatement() {
        int i = current;
        if (i >= tokens.size()) return false;
        if (tokens.get(i).type() == DO) i++;
        if (i < tokens.size()
                && (tokens.get(i).type() == NB || tokens.get(i).type() == TRY)) i++;
        if (i >= tokens.size() || tokens.get(i).type() != SELECT) return false;
        i++;

        if (i < tokens.size()
                && (tokens.get(i).type() == FIRST
                        || (tokens.get(i).type() == IDENT
                                && tokens.get(i).lexeme().equals("first")))) {
            i++;
        } else if (i < tokens.size()
                && tokens.get(i).type() == IDENT
                && (tokens.get(i).lexeme().equals("first")
                        || tokens.get(i).lexeme().equals("fair")
                        || tokens.get(i).lexeme().equals("random"))) {
            i++;
        }
        return i < tokens.size()
                && (tokens.get(i).type() == LBRACE
                        || tokens.get(i).type() == OVER
                        || (tokens.get(i).type() == IDENT
                                && tokens.get(i).lexeme().equals("over")));
    }

    /** A select arm participates in one atomic selection; only the enclosing select sets its wait mode. */
    private void rejectPerArmChannelWaitModifier() {
        if ((check(NB) || check(TRY))
                && (checkNext(READCH) || checkNext(WRITECH))) {
            Token modifier = peek();
            Token operation = tokens.get(current + 1);
            throw error(modifier,
                    "select arm cannot use '" + modifier.lexeme() + " "
                            + operation.lexeme()
                            + "'; put the wait mode on the enclosing select "
                            + "('select', 'nb select', or 'try select') and use "
                            + "plain 'readch' or 'writech' in each arm");
        }
    }

    private Ast.SelectStmt parseStaticSelectStatement() {
        boolean explicitDo = match(DO);
        Ast.WaitMode mode = Ast.WaitMode.BLOCKING;
        if (match(NB)) mode = Ast.WaitMode.NONBLOCKING;
        else if (match(TRY)) {
            if (explicitDo) {
                throw error(previous(),
                        "'do try select' is not supported; use 'try select'");
            }
            mode = Ast.WaitMode.IMMEDIATE;
        }

        consume(SELECT, "expected 'select'");
        return parseStaticSelectBody(mode, explicitDo);
    }

    private Ast.SelectStmt parseStaticSelectBody(Ast.WaitMode mode, boolean explicitDo) {
        Ast.SelectPolicy policy = parseSelectPolicy();
        boolean endDelimited = match(OVER) || matchContextualIdentifier("over");
        if (!endDelimited) {
            consume(LBRACE,
                    "static select requires '{ ... }' or 'select over ... end'; "
                            + "use 'select from cases' for dynamic select");
        }

        List<Ast.SelectArm> arms = new ArrayList<>();
        boolean seenDefault = false;
        while (!(endDelimited ? check(END) : check(RBRACE)) && !check(EOF)) {
            if (matchContextualKeyword("when", "case")) {
                if (seenDefault) {
                    throw error(previous(), "select default arm must be last");
                }
                rejectPerArmChannelWaitModifier();
                Ast.ChannelOperation operation;
                Ast.Expr channel;
                Ast.Expr value = null;
                Ast.BindingKind bindingKind = null;
                String bindingName = null;
                Ast.TypeRef bindingType = null;
                boolean mutableReferent = false;

                if (match(READCH) || match(AWAIT)) {
                    Token.Type op = previous().type();
                    operation = op == READCH ? Ast.ChannelOperation.READ : Ast.ChannelOperation.AWAIT;
                    channel = parseExpression();
                    consume(COLON, (op == READCH ? "readch" : "await") + " select arm requires ':'");
                    SelectReadBinding binding = parseSelectReadBinding();
                    bindingKind = binding.kind();
                    bindingName = binding.name();
                    bindingType = binding.type();
                    mutableReferent = binding.mutableReferent();
                } else if (match(WRITECH)) {
                    operation = Ast.ChannelOperation.WRITE;
                    channel = parseExpression();
                    consume(COMMA, "writech select arm requires 'channel, value'");
                    value = parseExpression();
                    consume(COLON, "writech select arm requires ':'");
                    // Typed write arms have no value binding. Arrow is optional
                    // compatibility syntax and cannot alter send admission.
                    // The common arm boundary consumes the arrow exactly once.
                } else {
                    // Legacy binding-first: when const msg = readch(input) -> { ... }
                    if (match(MUT)) {
                        bindingKind = Ast.BindingKind.LET;
                        mutableReferent = true;
                    } else if (isBindingKind(peek().type())) {
                        bindingKind = parseBindingKind();
                        mutableReferent = parseReferentMutability(bindingKind);
                    } else {
                        bindingKind = Ast.BindingKind.CONST;
                    }
                    bindingName = consume(IDENT, "select read case requires binding name").lexeme();
                    consume(EQUAL, "select case binding requires '='");
                    rejectPerArmChannelWaitModifier();
                    consume(READCH, "select case binding requires readch");
                    consume(LPAREN, "binding-first select readch requires '('");
                    channel = parseExpression();
                    consume(RPAREN, "binding-first select readch requires ')'");
                    if (!check(ARROW)) {
                        throw error(peek(), "binding-first select case requires '->'");
                    }
                    operation = Ast.ChannelOperation.READ;
                }

                // Accept the canonical '->' arm boundary while preserving
                // legacy arrowless select arms through the formatter migration.
                // Fat arrows remain reserved for type-level callable signatures.
                if (check(FAT_ARROW)) {
                    throw error(peek(),
                            "select executable arms use the slim arrow '->'; '=>' is not an arm delimiter");
                }
                match(ARROW);
                if (check(ARROW) || check(FAT_ARROW)) {
                    throw error(peek(), "select arm permits exactly one slim arrow '->'");
                }
                if (!check(LBRACE)) {
                    throw error(peek(), "select arm requires a braced body '{ ... }', optionally introduced by '->'");
                }
                List<Ast.Stmt> body = parseBlock();
                arms.add(new Ast.SelectArm(
                        operation,
                        channel,
                        value,
                        bindingKind,
                        bindingName,
                        body,
                        mutableReferent,
                        bindingType));
                continue;
            }

            if (match(DEFAULT)) {
                if (seenDefault) {
                    throw error(previous(), "select permits at most one default arm");
                }
                seenDefault = true;
                consume(COLON, "select default arm requires ':'");
                if (check(FAT_ARROW)) {
                    throw error(peek(),
                            "select default arms use the slim arrow '->'; '=>' is not an arm delimiter");
                }
                match(ARROW);
                if (!check(LBRACE)) {
                    throw error(peek(), "select default arm requires a braced body '{ ... }'");
                }
                List<Ast.Stmt> body = parseBlock();
                arms.add(new Ast.SelectArm(
                        Ast.ChannelOperation.DEFAULT,
                        null,
                        null,
                        null,
                        null,
                        body));
                continue;
            }

            throw error(peek(), "expected when/case/default inside select");
        }

        if (endDelimited) {
            consume(END, "expected 'end' after select over");
        } else {
            consume(RBRACE, "expected '}' after select");
        }
        if (arms.isEmpty()) {
            throw error(previous(), "select requires at least one arm");
        }
        return new Ast.SelectStmt(mode, policy, arms, explicitDo);
    }

    private record SelectReadBinding(
            Ast.BindingKind kind, String name, Ast.TypeRef type, boolean mutableReferent) { }

    /**
     * Typed arrow bindings deliberately require a result type; the legacy
     * untyped `const name { ... }` form remains source compatible.
     * This parser never speculatively consumes an event while checking types.
     */
    private SelectReadBinding parseSelectReadBinding() {
        if (check(LBRACE) || check(ARROW)) {
            // A typed operation can return void; it has no value to bind.
            return new SelectReadBinding(null, null, null, false);
        }
        Ast.BindingKind kind = Ast.BindingKind.CONST;
        boolean mutable = false;
        if (isBindingKind(peek().type())) {
            kind = parseBindingKind();
            mutable = parseReferentMutability(kind);
            if (check(IDENT) && (checkNext(LBRACE) || checkNext(ARROW) || checkNext(FAT_ARROW))) {
                String name = advance().lexeme();
                return new SelectReadBinding(kind, name, null, mutable);
            }
        }
        Ast.TypeRef type = parseTypeRef();
        String name = consume(IDENT, "typed select arm requires a binding name").lexeme();
        if (!check(ARROW)) {
            if (!check(LBRACE)) {
                throw error(peek(), "select arm requires a braced body '{ ... }'");
            }
            throw error(peek(), "typed select arm requires '->' before its body");
        }
        return new SelectReadBinding(kind, name, type, mutable);
    }

    private Ast.SelectPolicy parseSelectPolicy() {
        if (match(FIRST)) return Ast.SelectPolicy.PRIORITY;
        if (check(IDENT) && peek().lexeme().equals("first")) {
            advance();
            return Ast.SelectPolicy.PRIORITY;
        }
        if (check(IDENT) && peek().lexeme().equals("fair")) {
            advance();
            return Ast.SelectPolicy.FAIR;
        }
        if (check(IDENT) && peek().lexeme().equals("random")) {
            advance();
            return Ast.SelectPolicy.RANDOM;
        }
        return Ast.SelectPolicy.FAIR;
    }

    private Ast.Stmt parseDoWhile() {
        List<Ast.Stmt> body = parseBlock();
        consume(WHILE, "expected 'while' after do-while body");
        consume(LPAREN, "post-test do-while requires 'while (<condition>);'");
        if (check(RPAREN)) {
            throw error(peek(), "do-while requires a condition; use 'loop { ... }' for an unconditional loop");
        }
        Ast.Expr condition = parseExpression();
        consume(RPAREN, "expected ')' after do-while condition");
        consume(SEMICOLON, "do-while must end with ';'");
        return new Ast.DoWhileStmt(body, condition);
    }

    private Ast.Stmt parseFor(boolean asyncIteration) {
        if (!match(LPAREN)) {
            if (looksLikeUnparenthesizedForOf()) {
                if (!isBindingKind(peek().type())) {
                    throw forOfUndeclaredBindingError();
                }
                Ast.BindingKind kind = parseForOfBindingKind();
                boolean mutableReferent = parseReferentMutability(kind);
                if (looksLikeTypedForOfBinding()) {
                    throw typedForOfBindingError();
                }
                if (looksLikeForOfDestructurePattern()) {
                    List<Ast.DestructureBinding> bindings =
                            parseForSequenceBindings(kind, mutableReferent);
                    consume(OF, "iterator destructuring uses 'for [a, b] of iterable'");
                    Ast.Expr iterable = parseExpression();
                    return new Ast.ForOfDestructureStmt(bindings, iterable, asyncIteration, parseLoopBody());
                }
                String name = consume(IDENT, "iterator for-loop requires a binding name").lexeme();
                consume(OF, "iterator for-loop shorthand uses 'for x of iterable'");
                Ast.Expr iterable = parseExpression();
                return new Ast.ForOfStmt(
                        kind, name, iterable, asyncIteration, parseLoopBody(), mutableReferent);
            }

            if (asyncIteration) {
                throw error(peek(), "'for await' requires iterator syntax: for await x of asyncIterable");
            }
            if (looksLikeTypedForOfBinding()) {
                throw typedForOfUndeclaredBindingError();
            }

            Ast.Stmt initializer = parseUnparenthesizedForInitializer();
            consume(SEMICOLON, "expected ';' after for initializer");
            Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
            consume(SEMICOLON, "expected ';' after for condition");
            Ast.Expr update = check(LBRACE) || check(DO) ? null : parseForUpdate();
            return new Ast.ForStmt(initializer, condition, update, parseLoopBody());
        }

        if (isBindingKind(peek().type())) {
            Ast.BindingKind kind = parseBindingKind();
            rejectConstTypeDeclaration(kind);
            boolean mutableReferent = parseReferentMutability(kind);
            if (looksLikeTypedForOfBinding()) {
                requireForOfBindingKind(kind);
                throw typedForOfBindingError();
            }
            if (check(LBRACKET)) {
                requireForOfBindingKind(kind);
                List<Ast.DestructureBinding> bindings =
                        parseForSequenceBindings(kind, mutableReferent);
                consume(OF, "expected 'of' after for-of destructure pattern");
                Ast.Expr iterable = parseExpression();
                consume(RPAREN, "expected ')' after for-of header");
                return new Ast.ForOfDestructureStmt(bindings, iterable, asyncIteration, parseLoopBody());
            }
            if (check(IDENT) && checkNext(OF)) {
                requireForOfBindingKind(kind);
                String name = advance().lexeme();
                consume(OF, "expected 'of' in for-of loop");
                Ast.Expr iterable = parseExpression();
                consume(RPAREN, "expected ')' after for-of header");
                return new Ast.ForOfStmt(
                        kind, name, iterable, asyncIteration, parseLoopBody(), mutableReferent);
            }

            if (asyncIteration) {
                throw error(peek(), "'for await' requires iterator syntax: for await (x of asyncIterable)");
            }
            Ast.TypeRef type = null;
            String name;
            if (check(IDENT) && checkNext(EQUAL)) name = advance().lexeme();
            else {
                type = parseTypeRef();
                name = consume(IDENT, "expected loop initializer binding name").lexeme();
            }
            consume(EQUAL, "for initializer binding requires '='");
            Ast.Expr initializer = parseExpression();
            Ast.BindingStmt init = new Ast.BindingStmt(
                    kind, type, name, initializer, mutableReferent);
            consume(SEMICOLON, "expected ';' after for initializer");
            Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
            consume(SEMICOLON, "expected ';' after for condition");
            Ast.Expr update = check(RPAREN) ? null : parseForUpdate();
            consume(RPAREN, "expected ')' after for header");
            return new Ast.ForStmt(init, condition, update, parseLoopBody());
        }

        if (looksLikeTypedForOfBinding()) {
            throw typedForOfUndeclaredBindingError();
        }

        if (asyncIteration) {
            if (looksLikeForOfDestructurePattern() || (check(IDENT) && checkNext(OF))) {
                throw forOfUndeclaredBindingError();
            }
            throw error(peek(), "'for await' requires iterator syntax: for await (const x of asyncIterable)");
        }

        if (looksLikeTypedForInitializer()) {
            Ast.TypeRef type = parseTypeRef();
            String name = consume(IDENT, "expected typed loop binding name").lexeme();
            consume(EQUAL, "typed for initializer requires '='");
            Ast.BindingStmt init = new Ast.BindingStmt(
                    Ast.BindingKind.LET,
                    type,
                    name,
                    parseExpression());
            consume(SEMICOLON, "expected ';' after for initializer");
            Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
            consume(SEMICOLON, "expected ';' after for condition");
            Ast.Expr update = check(RPAREN) ? null : parseForUpdate();
            consume(RPAREN, "expected ')' after for header");
            return new Ast.ForStmt(init, condition, update, parseLoopBody());
        }

        if (looksLikeForOfDestructurePattern()) {
            throw forOfUndeclaredBindingError();
        }

        if (check(IDENT) && checkNext(OF)) {
            throw forOfUndeclaredBindingError();
        }

        Ast.Stmt initializer = null;
        if (!check(SEMICOLON)) initializer = new Ast.ExprStmt(parseExpression());
        consume(SEMICOLON, "expected ';' after for initializer");
        Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
        consume(SEMICOLON, "expected ';' after for condition");
        Ast.Expr update = check(RPAREN) ? null : parseForUpdate();
        consume(RPAREN, "expected ')' after for header");
        return new Ast.ForStmt(initializer, condition, update, parseLoopBody());
    }

    private boolean looksLikeUnparenthesizedForOf() {
        int mark = current;
        try {
            if (isBindingKind(peek().type())) {
                parseBindingKind();
                match(MUT);
                if (looksLikeTypedForOfBinding()) return true;
            }
            if (looksLikeForOfDestructurePattern()) return true;
            return check(IDENT) && checkNext(OF);
        } finally {
            current = mark;
        }
    }

    private boolean looksLikeTypedForOfBinding() {
        int mark = current;
        try {
            parseTypeRef();
            return check(IDENT) && checkNext(OF);
        } catch (IllegalArgumentException ignored) {
            return false;
        } finally {
            current = mark;
        }
    }

    private IllegalArgumentException typedForOfBindingError() {
        return error(peek(),
                "for-of element types are inferred; use 'for const/val/let name of iterable' "
                        + "(optionally 'let mut') and constrain/cast the iterable instead");
    }

    private IllegalArgumentException typedForOfUndeclaredBindingError() {
        int mark = current;
        Token name;
        try {
            parseTypeRef();
            name = consume(IDENT, "expected iterator binding name after type");
        } finally {
            current = mark;
        }
        return error(name,
                "variable used before declared '" + name.lexeme()
                        + "'; for-of bindings require an explicit const/val/let declaration");
    }

    private boolean looksLikeForOfDestructurePattern() {
        if (!check(LBRACKET)) return false;
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LBRACKET) depth++;
            else if (type == RBRACKET) {
                depth--;
                if (depth == 0) {
                    return i + 1 < tokens.size() && tokens.get(i + 1).type() == OF;
                }
            }
        }
        return false;
    }

    private Ast.Stmt parseUnparenthesizedForInitializer() {
        if (check(SEMICOLON)) return null;

        if (isBindingKind(peek().type())) {
            Ast.BindingKind kind = parseBindingKind();
            rejectConstTypeDeclaration(kind);
            boolean mutableReferent = parseReferentMutability(kind);
            Ast.TypeRef type = null;
            String name;
            if (check(IDENT) && checkNext(EQUAL)) {
                name = advance().lexeme();
            } else {
                type = parseTypeRef();
                name = consume(IDENT, "expected loop initializer binding name").lexeme();
            }
            consume(EQUAL, "for initializer binding requires '='");
            return new Ast.BindingStmt(
                    kind, type, name, parseExpression(), mutableReferent);
        }

        if (looksLikeTypedForInitializer()) {
            Ast.TypeRef type = parseTypeRef();
            String name = consume(IDENT, "expected typed loop binding name").lexeme();
            consume(EQUAL, "typed for initializer requires '='");
            return new Ast.BindingStmt(Ast.BindingKind.LET, type, name, parseExpression());
        }

        return new Ast.ExprStmt(parseExpression());
    }

    private boolean looksLikeTypedForInitializer() {
        int mark = current;
        try {
            parseTypeRef();
            return check(IDENT) && checkNext(EQUAL);
        } catch (IllegalArgumentException ignored) {
            return false;
        } finally {
            current = mark;
        }
    }

    private Ast.Expr parseForUpdate() {
        int mark = current;
        if (check(IDENT)) {
            String name = advance().lexeme();
            if (matchAdjacentPair(PLUS)) {
                Ast.NameExpr target = new Ast.NameExpr(name);
                return new Ast.AssignExpr(
                        target,
                        new Ast.BinaryExpr("+", new Ast.NameExpr(name), new Ast.LiteralExpr(1L)));
            }
            if (matchAdjacentPair(MINUS)) {
                Ast.NameExpr target = new Ast.NameExpr(name);
                return new Ast.AssignExpr(
                        target,
                        new Ast.BinaryExpr("-", new Ast.NameExpr(name), new Ast.LiteralExpr(1L)));
            }
            current = mark;
        }
        return parseExpression();
    }

    private List<Ast.DestructureBinding> parseForSequenceBindings(
            Ast.BindingKind inheritedKind,
            boolean inheritedMutableReferent) {
        consume(LBRACKET, "expected '[' to start for-of destructure pattern");
        if (check(RBRACKET)) throw error(peek(), "for-of destructure pattern cannot be empty");

        if (inheritedKind == null) {
            throw forOfUndeclaredBindingError();
        }

        List<Ast.DestructureBinding> bindings = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        Ast.BindingKind currentKind = inheritedKind;
        boolean currentMutableReferent = inheritedMutableReferent;
        do {
            if (isBindingKind(peek().type())) {
                currentKind = parseForOfBindingKind();
                currentMutableReferent = parseReferentMutability(currentKind);
            }
            boolean rest = match(ELLIPSIS);
            if (isDiscardToken(peek())) {
                if (rest) throw error(peek(), "rest destructure binding cannot be a discard");
                advance();
                bindings.add(Ast.DestructureBinding.discard());
            } else {
                String name = consume(IDENT, "expected binding name in for-of destructure pattern").lexeme();
                if (!names.add(name)) {
                    throw error(previous(), "duplicate binding '" + name + "' in for-of destructure pattern");
                }
                bindings.add(new Ast.DestructureBinding(
                        currentKind, name, rest, currentMutableReferent));
            }
            if (rest && check(COMMA)) {
                throw error(peek(), "rest destructure binding must be the final binding");
            }
        } while (match(COMMA));

        consume(RBRACKET, "expected ']' after for-of destructure pattern");
        return List.copyOf(bindings);
    }

    private List<Ast.Stmt> parseLoopBody() {
        if (check(LBRACE)) return parseBlock();
        if (!match(DO)) {
            throw error(peek(), "loop body must use '{ ... }' or 'do ... done'");
        }

        List<Ast.Stmt> body = new ArrayList<>();
        while (!check(EOF) && !isBareDoneDelimiter()) {
            body.add(parseStatement());
        }
        consume(DONE, "expected 'done' to close loop body");
        return body;
    }

    private boolean isBareDoneDelimiter() {
        return check(DONE) && !reservedCallableNameFollowedByInvocation(current);
    }

    /**
     * 'type' in a binding's type position is a legal named-alias marker, e.g.
     * 'const type T value = ...'. Only 'const type T = ...' attempts to
     * re-declare a compile-time type as a value binding.
     */
    private void rejectConstTypeDeclaration(Ast.BindingKind kind) {
        if (kind == Ast.BindingKind.CONST && check(TYPE)
                && current + 2 < tokens.size()
                && isIdentifierLikeToken(tokens.get(current + 1).type())
                && tokens.get(current + 2).type() == EQUAL) {
            throw error(peek(), "'const type' is not a declaration: types are immutable; use 'type T = ...;'");
        }
    }

    private boolean looksLikePrimitiveTypeFirstImmutableBinding() {
        if (!check(IDENT) || current + 2 >= tokens.size()) return false;
        String type = peek().lexeme();
        if (!(type.equals("int") || type.equals("bool")
                || type.equals("string") || type.equals("String"))) return false;
        return tokens.get(current + 1).type() == IDENT
                && tokens.get(current + 2).type() == EQUAL;
    }

    private Ast.BindingStmt parsePrimitiveTypeFirstImmutableBinding() {
        Ast.TypeRef type = parseTypeRef();
        String name = consume(IDENT,
                "expected binding name after immutable-default local type").lexeme();
        consume(EQUAL, "immutable-default local binding requires initializer");
        Ast.Expr initializer = parseExpression();
        consumeExpressionStatementTerminator(
                initializer, "immutable-default binding should end with ';'");
        return new Ast.BindingStmt(
                Ast.BindingKind.CONST, type, name, initializer, false, null);
    }

    /**
     * Resolve contextual Map.of(...) / Map.of() only in an immediate, explicitly
     * typed binding. This is a syntax-level witness, NOT an inferred runtime
     * dictionary. Aliases, generics and implicit expected types fail closed
     * until independently verified typed-HIR contextual lowering exists.
     */
    private Ast.Expr resolveDirectContextualEmptyMap(
            Ast.TypeRef declaredType, Ast.Expr initializer) {
        boolean untypedEntries = initializer instanceof Ast.MapLiteralExpr map
                && map.mapType() == null;
        if (!(initializer instanceof Ast.ContextualEmptyMapExpr) && !untypedEntries) {
            return initializer;
        }
        if (declaredType == null || !declaredType.name().equals("Map")
                || declaredType.inferArguments() || declaredType.arguments().size() != 2) {
            String spelling = untypedEntries ? "Map.of(...)" : "Map.of()";
            throw error(previous(), spelling
                    + " requires a directly declared, fully typed Map<K,V> binding");
        }
        // Never lose the exact key/value witness. TypeChecker and
        // OwnershipChecker still independently validate both domains.
        return initializer instanceof Ast.MapLiteralExpr map
                ? new Ast.MapLiteralExpr(declaredType, map.entries())
                : new Ast.TypedEmptyAggregateExpr(declaredType);
    }

    private Ast.BindingStmt parseBindingStatement() {
        return parseBindingStatement(List.of());
    }

    private Ast.BindingStmt parseBindingStatement(List<Ast.Annotation> annotations) {
        Ast.BindingKind kind = parseBindingKind();
        rejectConstTypeDeclaration(kind);
        boolean mutableReferent = parseReferentMutability(kind);
        Ast.TypeRef type = null;
        String name;
        boolean functionShorthand = match(FNC);
        if (functionShorthand && (kind != Ast.BindingKind.CONST || mutableReferent)) {
            throw error(previous(), "lambda binding shorthand requires 'const fnc'");
        }
        if (functionShorthand) {
            name = consume(IDENT, "expected function binding name").lexeme();
        } else if (check(IDENT) && nameIsFollowedByIoDeclarationRead()) {
            name = advance().lexeme(); // `val name << source;`
        } else if (looksLikeNamedCallableBindingSignature()) {
            name = advance().lexeme();
            type = parseNamedCallableBindingSignature();
        } else if (check(IDENT) && checkNext(COLON)) {
            name = advance().lexeme();
            consume(COLON, "expected ':' after binding name");
            type = parseTypeRef();
        } else if (check(IDENT) && checkNext(EQUAL)) {
            name = advance().lexeme();
        } else {
            type = parseTypeRef();
            name = consume(IDENT, "expected binding name").lexeme();
        }
        if (!annotations.isEmpty()) {
            if (type == null) {
                throw error(previous(), "type metadata annotations require an explicit declared type");
            }
            type = applyTypeMetadataAnnotations(type, annotations, true);
        }
        if (!functionShorthand && checkIoDeclarationRead()) {
            // Declaration-first read: exactly `val name = readch source;`.
            if (kind == Ast.BindingKind.CONST) throw constChannelRead(peek());
            advance();
            advance(); // <<
            Ast.Expr read = Ast.IoShorthand.read(parseExpression());
            consumeExpressionStatementTerminator(read, "channel read should end with ';'");
            return new Ast.BindingStmt(kind, type, name, read, mutableReferent);
        }
        consume(EQUAL, "binding requires initializer");
        String inferredTypeName = null;
        Ast.Expr initializer;
        if (isContextualIdentifier("infer") && checkNext(LT)) {
            advance(); // contextual 'infer'
            consume(LT, "expected '<' after infer");
            inferredTypeName = consume(IDENT, "expected inferred type name in infer<T>").lexeme();
            consume(GT, "expected '>' after inferred type name");
            if (kind != Ast.BindingKind.CONST || mutableReferent) {
                throw error(previous(), "infer<T> requires an immutable const binding");
            }
            if (type == null || !type.name().equals(inferredTypeName)
                    || !type.arguments().isEmpty() || type.inferArguments()) {
                throw error(previous(), "binding type must match infer<T> exactly");
            }
            if (match(STRUCT)) {
                initializer = parseObjectLiteral(Ast.ObjectKind.INFERRED_STRUCT, List.of());
            } else if (matchContextualIdentifier("record")) {
                // Explicit alias capture remains available for structural records.
                initializer = parseObjectLiteral(Ast.ObjectKind.INFERRED_RECORD, List.of());
            } else if (matchContextualKeyword("tuple")) {
                consume(LPAREN, "expected '(' after infer<T> tuple");
                List<Ast.Expr> items = parseArgumentsUntil(RPAREN);
                consume(RPAREN, "expected ')' after inferred tuple");
                if (items.isEmpty()) {
                    throw error(previous(), "infer<T> tuple requires at least one element");
                }
                initializer = new Ast.TupleExpr(items);
            } else {
                throw error(peek(), "infer<T> requires a struct {...}, record {...}, or tuple (...) literal");
            }
        } else {
            initializer = parseExpression();
        }
        if (functionShorthand && !(initializer instanceof Ast.LambdaExpr)) {
            throw error(previous(), "'const fnc' requires a lambda initializer");
        }
        initializer = resolveDirectContextualEmptyMap(type, initializer);
        consumeExpressionStatementTerminator(initializer, "binding should end with ';'");
        return new Ast.BindingStmt(kind, type, name, initializer, mutableReferent, inferredTypeName);
    }

    private Ast.TypeRef applyTypeMetadataAnnotations(
            Ast.TypeRef type,
            List<Ast.Annotation> annotations,
            boolean rejectUnknown) {
        java.util.LinkedHashMap<String, Ast.TypeRef> additions = new java.util.LinkedHashMap<>();
        for (Ast.Annotation annotation : annotations) {
            String annotationName = annotation.name();
            if (annotationName.equals("NamedParams")) {
                if (annotation.arguments().isEmpty()) {
                    throw error(previous(), "@NamedParams requires at least one name=value entry");
                }
                for (Ast.TypeRef argument : annotation.arguments()) {
                    if (!argument.isNamedParameter()) {
                        throw error(previous(), "@NamedParams arguments must use name=value syntax");
                    }
                    putMetadataAnnotation(additions, argument.namedParameterName(), argument.namedParameterValue());
                }
                continue;
            }

            String parameterName = switch (annotationName) {
                case "GrowthPolicy" -> "growth_policy";
                case "Allocator" -> "allocator";
                case "Align" -> "align";
                case "InlineCapacity" -> "inline_capacity";
                case "MaxCapacity" -> "max_capacity";
                case "Storage" -> "storage";
                case "Rank" -> "rank";
                default -> null;
            };
            if (parameterName == null) {
                if (rejectUnknown) {
                    throw error(previous(), "unsupported local annotation @" + annotationName
                            + "; local annotations must map to named type parameters");
                }
                continue;
            }
            if (annotation.arguments().size() != 1 || annotation.arguments().getFirst().isNamedParameter()) {
                throw error(previous(), "@" + annotationName + " requires exactly one positional compile-time value");
            }
            putMetadataAnnotation(additions, parameterName, annotation.arguments().getFirst());
        }
        if (additions.isEmpty()) return type;

        List<Ast.TypeRef> positional = new ArrayList<>();
        java.util.TreeMap<String, Ast.TypeRef> named = new java.util.TreeMap<>();
        List<Ast.TypeRef> shapes = new ArrayList<>();
        for (Ast.TypeRef argument : type.arguments()) {
            if (argument.isNamedParameter()) {
                String key = argument.namedParameterName();
                named.put(key, argument);
            } else if (argument.isSequenceShape()) shapes.add(argument);
            else positional.add(argument);
        }
        for (java.util.Map.Entry<String, Ast.TypeRef> entry : additions.entrySet()) {
            if (named.containsKey(entry.getKey())) {
                throw error(previous(), "named type parameter '" + entry.getKey()
                        + "' is specified both inline and by annotation");
            }
            named.put(entry.getKey(), Ast.TypeRef.namedParameter(entry.getKey(), entry.getValue()));
        }
        positional.addAll(named.values());
        positional.addAll(shapes);
        return new Ast.TypeRef(type.name(), positional, type.inferArguments());
    }

    private void putMetadataAnnotation(
            java.util.Map<String, Ast.TypeRef> values,
            String name,
            Ast.TypeRef value) {
        if (values.putIfAbsent(name, value) != null) {
            throw error(previous(), "duplicate named type parameter '" + name + "' in annotations");
        }
    }

    private Ast.DestructureStmt parseDestructure(
            Ast.DestructureKind kind,
            Ast.BindingKind inheritedKind,
            boolean inheritedMutableReferent) {
        Token.Type close;
        if (kind == Ast.DestructureKind.SEQUENCE) {
            if (match(LPAREN)) close = RPAREN;
            else {
                consume(LBRACKET, "expected '['");
                close = RBRACKET;
            }
        } else {
            consume(LBRACE, "expected '{'");
            close = RBRACE;
        }

        if (check(close)) throw error(peek(), "destructure pattern cannot be empty");

        List<Ast.DestructureBinding> bindings = new ArrayList<>();
        Ast.BindingKind currentKind = inheritedKind;
        boolean currentMutableReferent = inheritedMutableReferent;
        do {
            if (isBindingKind(peek().type())) {
                currentKind = parseBindingKind();
                currentMutableReferent = parseReferentMutability(currentKind);
            }
            boolean rest = match(ELLIPSIS);

            if (isDiscardToken(peek())) {
                if (rest) throw error(peek(), "rest destructure binding cannot be a discard");
                if (kind == Ast.DestructureKind.OBJECT) {
                    throw error(peek(), "bare '_' discard is only valid in sequence destructuring");
                }
                advance();
                bindings.add(Ast.DestructureBinding.discard());
                continue;
            }

            if (currentKind == null) {
                throw error(peek(), "destructure binding kind must be declared before the first binding");
            }
            String name = consume(IDENT, "expected binding name in destructure").lexeme();
            bindings.add(new Ast.DestructureBinding(
                    currentKind, name, rest, currentMutableReferent));
            if (rest && check(COMMA)) {
                throw error(peek(), "rest destructure binding must be the final binding");
            }
        } while (match(COMMA));

        consume(close, kind == Ast.DestructureKind.SEQUENCE ? "expected ']'" : "expected '}'");
        consume(EQUAL, "expected '=' after destructure pattern");
        Ast.Expr initializer = parseExpression();
        consumeExpressionStatementTerminator(initializer, "destructure should end with ';'");
        return new Ast.DestructureStmt(kind, bindings, initializer);
    }

    private boolean looksLikeDestructure() {
        if (current + 1 >= tokens.size()) return false;
        Token first = tokens.get(current + 1);
        return isBindingKind(first.type()) || isDiscardToken(first);
    }

    private boolean looksLikePrefixedDestructure() {
        int openIndex = current + 1;
        if (openIndex < tokens.size() && tokens.get(openIndex).type() == MUT) openIndex++;
        if (openIndex + 1 >= tokens.size()) return false;
        Token.Type open = tokens.get(openIndex).type();
        Token.Type close;
        if (open == LBRACKET) close = RBRACKET;
        else if (open == LBRACE) close = RBRACE;
        else if (open == LPAREN) close = RPAREN;
        else return false;

        int depth = 0;
        for (int i = openIndex; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == open) depth++;
            else if (type == close) {
                depth--;
                if (depth == 0) {
                    return i + 1 < tokens.size() && tokens.get(i + 1).type() == EQUAL;
                }
            }
        }
        return false;
    }

    private boolean isDiscardToken(Token token) {
        return token.type() == IDENT && token.lexeme().equals("_");
    }


    /**
     * AttoLang guard inverts a boolean precondition and runs its mandatory
     * terminal failure block. The positive path stays in the current scope.
     * The canonical form has no 'else' or trailing 'fi'.
     *
     * Keep Option payload refinement closed until the type checker has
     * ownership-aware, stable payload projections; scalar boolean guards
     * lower through the existing checked IfStmt representation.
     */
    private Ast.Stmt parseGuard() {
        if (check(LBRACE)) throw error(peek(), "guard requires a boolean condition");
        // Presence narrowing is a separate ownership-checked language slice.
        // Reject it before a nominal `is` expression could be misinterpreted.
        for (int at = current; at + 1 < tokens.size() && tokens.get(at).type() != LBRACE; at++) {
            if (tokens.get(at).type() == IS
                    && tokens.get(at + 1).lexeme().equals("Some")) {
                throw error(tokens.get(at), "guard is Some requires ownership-checked Option narrowing");
            }
        }
        Ast.Expr condition = parseCondition();
        List<Ast.Stmt> failureBody = parseBlock();
        // A following ELSE can belong to an enclosing keyword-delimited
        // if. Only an immediately braced guard-else is unambiguously illegal.
        // Unattached ELSE/FI tokens remain rejected by the containing parser.
        if (check(ELSE) && checkNext(LBRACE)) {
            throw error(peek(), "guard has no 'else'; use guard condition { return; }");
        }
        if (!guardFailureTerminates(failureBody)) {
            throw error(previous(), "guard failure block must exit with return, break or continue");
        }
        return new Ast.IfStmt(
                List.of(new Ast.IfBranch(new Ast.UnaryExpr("!", condition), failureBody)),
                List.of());
    }

    private boolean guardFailureTerminates(List<Ast.Stmt> body) {
        if (body.isEmpty()) return false;
        Ast.Stmt last = body.getLast();
        if (last instanceof Ast.ReturnStmt || last instanceof Ast.BreakStmt
                || last instanceof Ast.ContinueStmt) return true;
        if (last instanceof Ast.BlockStmt block) return guardFailureTerminates(block.body());
        if (last instanceof Ast.IfStmt branch) {
            if (branch.elseBody().isEmpty() || !guardFailureTerminates(branch.elseBody()))
                return false;
            for (Ast.IfBranch arm : branch.branches()) {
                if (!guardFailureTerminates(arm.body())) return false;
            }
            return true;
        }
        return false;
    }

    private Ast.Stmt parseIf(Token.Type introducer, boolean explicitImperative) {
        List<Ast.IfBranch> branches = new ArrayList<>();
        List<Ast.BindingStmt> bindings = new ArrayList<>();
        // Inverting a refutable pattern does not establish its captures in the
        // then arm. Do not admit implicit narrowing without a scope proof.
        if (introducer != IF && (check(LET) || (check(LPAREN) && checkNext(LET)))) {
            throw error(peek(), "ifnot/unless do not support pattern bindings; use if");
        }
        ConditionHead head = parseConditionHead(false);
        if (introducer != IF && head.binding() != null) {
            throw error(previous(), "ifnot/unless do not support condition bindings; use if");
        }
        bindings.add(head.binding());
        Ast.Expr condition = introducer == IF
                ? head.predicate() : new Ast.UnaryExpr("!", head.predicate());

        // Canonical braces are self-delimiting. Prefix do-if owns a
        // keyword-delimited statement chain, never a braced spelling.
        if (check(LBRACE)) {
            if (canonicalAttoSource && explicitImperative) {
                throw error(peek(), "prefix 'do if' requires 'then', not a brace");
            }
            branches.add(new Ast.IfBranch(condition, parseConditionalBraceBody()));
            while (matchConditionalBranch()) {
                head = parseConditionHead(false);
                bindings.add(head.binding());
                condition = head.predicate();
                branches.add(new Ast.IfBranch(condition, parseConditionalBraceBody()));
            }

            List<Ast.Stmt> elseBody = List.of();
            if (match(ELSE)) elseBody = parseConditionalBraceBody();
            if (!canonicalAttoSource) consume(FI, "expected 'fi' to close if");
            return lowerConditionBindings(branches, bindings, elseBody);
        }

        if (canonicalAttoSource && !explicitImperative && !check(DO)) {
            consume(THEN, "bare keyword if requires 'do' for statements or 'then' for values");
            if (check(RETURN) || check(CARET)) {
                Ast.Expr value = parseConditionalValueArm();
                if (match(FI)) {
                    return lowerConditionBindings(
                            List.of(new Ast.IfBranch(condition, List.of(new Ast.ExprStmt(value)))),
                            bindings, List.of());
                }
                if (head.binding() != null) {
                    throw error(peek(), "functional if requires an unbound condition");
                }
                return new Ast.ExprStmt(parseValueIfElse(condition, value));
            }
            return parseActionIf(condition, head.binding());
        }

        // Canonical keyword-delimited form:
        //   if condition do
        //   elif condition do
        //   else
        //   fi
        //
        // Compatibility forms remain accepted while source migrates:
        // - 'if ... do' / 'elif ... do' are canonical imperative spellings.
        // - 'elseif' aliases canonical 'elif'.
        // - 'else if ... then' is accepted as a single-fi branch alias.
        match(SEMICOLON);
        consumeConditionalThen(introducer == IF ? "if" : introducer == IFNOT ? "ifnot" : "unless", explicitImperative);
        List<Ast.Stmt> body = parseUntil(ELSEIF, ELSE, FI);
        branches.add(new Ast.IfBranch(condition, body));

        while (matchConditionalBranch()) {
            head = parseConditionHead(false);
            bindings.add(head.binding());
            condition = head.predicate();
            match(SEMICOLON);
            consumeConditionalThen("elif", explicitImperative);
            body = parseUntil(ELSEIF, ELSE, FI);
            branches.add(new Ast.IfBranch(condition, body));
        }

        List<Ast.Stmt> elseBody = List.of();
        if (match(ELSE)) elseBody = parseUntil(FI);
        consume(FI, "expected 'fi' to close if");
        return lowerConditionBindings(branches, bindings, elseBody);
    }

    private Ast.Stmt parseActionIf(Ast.Expr condition, Ast.BindingStmt binding) {
        List<Ast.IfBranch> branches = new ArrayList<>();
        List<Ast.BindingStmt> bindings = new ArrayList<>();
        branches.add(new Ast.IfBranch(condition, parseConditionalActions()));
        bindings.add(binding);
        while (matchConditionalBranch()) {
            ConditionHead head = parseConditionHead(false);
            consume(THEN, "expected 'then' after elif condition");
            branches.add(new Ast.IfBranch(head.predicate(), parseConditionalActions()));
            bindings.add(head.binding());
        }
        List<Ast.Stmt> otherwise = match(ELSE) ? parseConditionalActions() : List.of();
        consume(FI, "expected 'fi' to close imperative if");
        return lowerConditionBindings(branches, bindings, otherwise);
    }

    private List<Ast.Stmt> parseConditionalActions() {
        List<Ast.Stmt> body = new ArrayList<>();
        do {
            consume(DO, "bare imperative if arms require 'do action'; use 'do if' for unmarked statement arms");
            Ast.Expr action = parseExpression();
            consumeExpressionStatementTerminator(action, "conditional action should end with ';'");
            body.add(new Ast.ExprStmt(action));
        } while (check(DO));
        return body;
    }

    private List<Ast.Stmt> parseConditionalBraceBody() {
        if (canonicalAttoSource) return parseBlock();
        consume(LBRACE, "expected '{' before conditional branch");
        List<Ast.Stmt> body = parseUntil(RBRACE, FI);
        if (match(RBRACE) && check(FI)) {
            Token redundant = previous();
            warnings.add(new SyntaxWarning(redundant.line(), redundant.column(),
                    "redundant '}' before 'fi'; fi closes the final conditional branch"));
        }
        // The caller consumes the branch separator or the final fi. Retain
        // the historical '} fi' spelling with a migration diagnostic.
        return body;
    }

    private void consumeConditionalThen(String branch, boolean prefixDo) {
        if (canonicalAttoSource) {
            consume(prefixDo ? THEN : DO, "expected '" + (prefixDo ? "then" : "do")
                    + "' after " + branch + " condition");
            return;
        }
        // Retain historical migration admission outside canonical source.
        if (match(DO, THEN)) return;
        throw error(peek(), "expected 'do' or 'then' after " + branch + " condition");
    }

    private boolean matchConditionalBranch() {
        if (match(ELSEIF)) return true; // Lexer maps both 'elif' and legacy 'elseif' here.
        // "else if" is a branch alias only when both keywords are on the
        // same source line. Otherwise a normal else body is allowed to begin
        // with a nested if statement on the following line.
        if (check(ELSE)
                && checkNext(IF)
                && peek().line() == tokens.get(current + 1).line()) {
            advance();
            advance();
            return true;
        }
        return false;
    }

    private record ConditionHead(Ast.BindingStmt binding, Ast.Expr predicate) { }

    private ConditionHead parseConditionHead(boolean whileHead) {
        boolean wrapped = check(LPAREN) && (checkNext(LET)
                || (whileHead && wholeConditionParentheses()) || hasParenthesizedConditionBinding());
        if (wrapped) advance();
        if (match(LET)) {
            Ast.Pattern pattern = parsePattern();
            if (pattern instanceof Ast.BindingPattern || pattern instanceof Ast.WildcardPattern) {
                throw error(previous(), "if/while let requires a refutable pattern, such as Some(value); use an as let capture for ordinary values");
            }
            consume(EQUAL, "conditional pattern requires '=' before its value");
            Ast.Expr predicate = new Ast.PatternTestExpr(parseBitwiseOr(), pattern);
            if (matchAdjacentPair(AMP)) {
                predicate = new Ast.BinaryExpr("&&", predicate, parseCondition());
            }
            if (wrapped) consume(RPAREN, "expected ')' after conditional pattern");
            return new ConditionHead(null, predicate);
        }
        Ast.Expr value = parseLogicalOr();
        Ast.BindingStmt binding = null;
        Ast.Expr predicate;
        if (check(AS) && checkNext(LET)) {
            advance();
            advance();
            String name = consume(IDENT, "condition binding requires a name").lexeme();
            if (!matchContextualKeyword("when")) {
                throw error(peek(), "condition binding requires 'when' and a boolean predicate");
            }
            binding = new Ast.BindingStmt(Ast.BindingKind.LET, null, name, value, false);
            predicate = parseCondition();
        } else {
            predicate = normalizeLegacyConditionPipe(value);
            while (match(COMMA)) {
                predicate = new Ast.BinaryExpr("&&", predicate,
                        normalizeLegacyConditionPipe(parseLogicalOr()));
            }
        }
        if (wrapped) consume(RPAREN, "expected ')' after condition");
        return new ConditionHead(binding, predicate);
    }

    private boolean wholeConditionParentheses() {
        int depth = 0;
        for (int i = current; i + 1 < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) depth++;
            if (type == RPAREN && --depth == 0) {
                Token.Type next = tokens.get(i + 1).type();
                // A postfix index/call/member or infix operator continues the
                // expression; its '(' belongs to that value, not the head.
                return next == LBRACE || next == DO || next == SEMICOLON;
            }
        }
        return false;
    }

    private boolean hasParenthesizedConditionBinding() {
        int depth = 0;
        int nestedValues = 0;
        for (int i = current; i + 1 < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) depth++;
            if (type == RPAREN && --depth == 0) return false;
            if (type == LBRACE || type == LBRACKET) nestedValues++;
            if (type == RBRACE || type == RBRACKET) nestedValues--;
            if (depth == 1 && nestedValues == 0 && type == AS
                    && tokens.get(i + 1).type() == LET && i + 3 < tokens.size()
                    && tokens.get(i + 3).lexeme().equals("when")) return true;
        }
        return false;
    }

    private Ast.Stmt lowerConditionBindings(List<Ast.IfBranch> branches,
            List<Ast.BindingStmt> bindings, List<Ast.Stmt> elseBody) {
        if (bindings.stream().allMatch(java.util.Objects::isNull)) {
            return new Ast.IfStmt(branches, elseBody);
        }
        // A later initializer is evaluated only if every preceding test failed.
        List<Ast.Stmt> fallback = elseBody;
        Ast.Stmt result = null;
        for (int i = branches.size() - 1; i >= 0; i--) {
            result = new Ast.IfStmt(List.of(branches.get(i)), fallback);
            if (bindings.get(i) != null) {
                result = new Ast.BlockStmt(List.of(bindings.get(i), result));
            }
            fallback = List.of(result);
        }
        return result;
    }

    private Ast.Expr parseCondition() {
        Ast.Expr expression = normalizeLegacyConditionPipe(parseLogicalOr());
        // Condition-only comma means logical AND and bare '|' means OR (spec ch.16).
        // Prefer && and || in new code.
        while (match(COMMA)) {
            expression = new Ast.BinaryExpr(
                    "&&",
                    expression,
                    normalizeLegacyConditionPipe(parseLogicalOr()));
        }
        return expression;
    }

    private Ast.Expr normalizeLegacyConditionPipe(Ast.Expr expr) {
        if (expr instanceof Ast.BinaryExpr binary) {
            String operator = binary.operator().equals("|") ? "||" : binary.operator();
            return new Ast.BinaryExpr(
                    operator,
                    normalizeLegacyConditionPipe(binary.left()),
                    normalizeLegacyConditionPipe(binary.right()));
        }
        return expr;
    }

    /**
     * Parse a standalone subject header, not a callable parameter list.
     * Scan only the bounded, balanced header to identify the closing single bar;
     * paired logical OR and nested grouping keep their ordinary grammar.
     * The real expression parser must consume exactly the delimited tokens.
     */
    private Ast.Expr parseDelimitedSubject(boolean allowProduct) {
        if (!match(PIPE)) return parseExpression();
        if (check(PIPE) || check(COMMA) || check(EOF)) {
            throw error(peek(), "expected a nonempty delimited subject");
        }
        java.util.ArrayDeque<Token.Type> closers = new java.util.ArrayDeque<>();
        int closing = -1;
        for (int i = current; i < tokens.size() && i - current < 4096; i++) {
            Token.Type kind = tokens.get(i).type();
            if (kind == EOF) break;
            if (kind == LPAREN) closers.push(RPAREN);
            else if (kind == LBRACKET) closers.push(RBRACKET);
            else if (kind == LBRACE) closers.push(RBRACE);
            else if (kind == RPAREN || kind == RBRACKET || kind == RBRACE) {
                if (closers.isEmpty() || closers.pop() != kind) {
                    throw error(tokens.get(i), "unbalanced delimited subject");
                }
            } else if (closers.isEmpty() && kind == PIPE) {
                if (i + 1 < tokens.size() && tokens.get(i + 1).type() == PIPE
                        && adjacent(tokens.get(i), tokens.get(i + 1))) {
                    i++;
                } else {
                    closing = i;
                    break;
                }
            }
        }
        if (closing < 0) throw error(peek(), "missing closing bar or delimited subject exceeds token budget");
        int saved = delimitedSubjectEnd;
        delimitedSubjectEnd = closing;
        try {
            List<Ast.Expr> subjects = new ArrayList<>();
            subjects.add(parseExpression());
            while (match(COMMA)) {
                if (!allowProduct) throw error(previous(), "multiple delimited switch subjects require checked product dispatch");
                if (current == closing) throw error(peek(), "trailing comma in delimited subject");
                if (subjects.size() >= 128) throw error(peek(), "delimited subject arity exceeds 128");
                subjects.add(parseExpression());
            }
            if (current != closing) throw error(peek(), "expected closing bar of delimited subject");
            consume(PIPE, "expected closing bar of delimited subject");
            return subjects.size() == 1 ? subjects.getFirst() : new Ast.TupleExpr(subjects);
        } finally {
            delimitedSubjectEnd = saved;
        }
    }

    private Ast.MatchStmt parseMatch(boolean procedural) {
        // Canonical match is ordered and spells its boundary explicitly:
        //   match value over
        //     on Pattern -> { ... }
        //   end
        //
        // Preserve the pre-over grammar as a compatibility surface. Legacy
        // match remains proof-checked unless the historical contextual
        // `first` marker is present.
        boolean legacyFirst = matchContextualIdentifier("first");
        boolean delimited = check(PIPE);

        boolean previousSuppression = suppressRefinementOperators;
        suppressRefinementOperators = true;
        Ast.Expr subject;
        try {
            subject = parseDelimitedSubject(true);
        } finally {
            suppressRefinementOperators = previousSuppression;
        }

        boolean canonicalOver = match(OVER) || matchContextualIdentifier("over");
        if (delimited && !canonicalOver) throw error(peek(), "delimited match requires 'over'");
        if (!canonicalOver) match(SEMICOLON);
        // "over { ... }" owns the outer braces, while "over ... end"
        // retains its established terminator. Neither form may consume the
        // enclosing function's closing brace as if it ended the match.
        boolean braced = canonicalOver && match(LBRACE);
        if (procedural && !canonicalOver) {
            throw error(peek(), "'do match' requires 'over' before its arms");
        }

        List<Ast.MatchArm> arms = new ArrayList<>();
        boolean seenDefault = false;
        boolean seenUnconditionalWildcard = false;
        while (!check(EOF) && !(braced ? check(RBRACE) : check(END))) {
            // 'on _' is a compatibility spelling for an unguarded fallback.
            // It must not make later arms (including 'default') unreachable.
            if (canonicalOver && seenUnconditionalWildcard) {
                throw error(peek(), "unguarded wildcard match arm must be last");
            }
            boolean defaultArm = false;
            if (canonicalOver) {
                defaultArm = match(DEFAULT);
                if (!defaultArm) {
                    if (seenDefault) throw error(peek(), "default match arm must be last");
                    if (!matchContextualIdentifier("on") && !check(IS)) {
                        if (check(ELSE)) throw error(peek(), "match fallback is 'default ->', not else");
                        throw error(peek(), "match arm must start with 'on' or 'is'");
                    }
                    // Accept legacy fallback spelling as a final wildcard.
                    defaultArm = match(DEFAULT);
                }
                if (defaultArm) {
                    if (seenDefault) throw error(previous(), "duplicate default match arm");
                    seenDefault = true;
                }
            }

            Ast.Pattern pattern;
            if (defaultArm || (!canonicalOver && match(ELSE))) {
                pattern = new Ast.WildcardPattern();
            } else {
                pattern = parsePattern();
            }

            Ast.Expr guard = null;
            if (matchWhenKeyword()) {
                if (defaultArm) throw error(previous(), "default match arm cannot have a guard");
                int previousGuardStart = matchGuardStartToken;
                matchGuardStartToken = current;
                try {
                    guard = parseExpression();
                } finally {
                    matchGuardStartToken = previousGuardStart;
                }
            }
            if (canonicalOver && pattern instanceof Ast.WildcardPattern && guard == null) {
                seenUnconditionalWildcard = true;
            }
            if (check(FAT_ARROW)) {
                throw error(peek(), "match implementations use the slim arrow '->'; '=>' is reserved for type definitions");
            }
            // Canonical braced match permits an immediately braced arm:
            //   do match x over { on Some(v) { ... } }
            // Legacy and over/end arms retain the explicit slim arrow.
            if (!match(ARROW) && !braced) {
                throw error(peek(), "match arms in 'over ... end' use '->'");
            }
            List<Ast.Stmt> body = parseBlock();
            if (canonicalAttoSource && procedural) body = normalizeTerminalVoidMatchArm(body);
            arms.add(new Ast.MatchArm(pattern, guard, body));
        }
        if (braced) consume(RBRACE, "expected '}' to close braced match");
        else consume(END, "expected 'end' to close match");
        if (arms.isEmpty()) {
            throw error(previous(), canonicalOver
                    ? "match requires at least one match arm"
                    : "match requires at least one arm");
        }

        // Canonical match is source-ordered. Legacy bare match retains the
        // old exclusivity/proof semantics unless explicitly written
        // `match first ...`.
        if (delimited) match(SEMICOLON);
        return new Ast.MatchStmt(subject, canonicalOver || legacyFirst, arms, procedural);
    }

    /**
     * A value match cannot be implemented by reinterpreting a procedural
     * return. Each arm yields exactly one expression; the return belongs to
     * MatchExpr rather than the enclosing callable's control flow.
     */
    private Ast.MatchExpr parseValueMatch() {
        return parseValueMatch(null);
    }

    /** A forward pipeline supplies the subject directly, without a synthetic call or lambda. */
    private Ast.MatchExpr parseValueMatch(Ast.Expr injectedSubject) {
        // A single result type is an expression constraint, not the subject type.
        // Preserve the historical <ret=T> spelling while preferring <T>.
        boolean canonicalFirst = matchContextualIdentifier("first");
        Ast.TypeRef declaredResult = null;
        if (match(LT)) {
            if (check(IDENT) && checkNext(EQUAL)) {
                String name = advance().lexeme();
                consume(EQUAL, "expected '=' in named match result parameter");
                if (!name.equals("ret")) {
                    throw error(previous(), "match supports only the named result parameter 'ret'");
                }
            }
            declaredResult = parseTypeRef();
            consume(GT, "match result parameter requires exactly one type argument and closing '>'");
        }

        // Selected canonical order: match first<T> subject over ...
        // Preserve historical match<T> first subject over ... as input.
        // Unlike ordinary match, the first form authorizes ordered overlaps.
        boolean legacyFirst = !canonicalFirst && matchContextualIdentifier("first");

        Ast.Expr subject = injectedSubject;
        if (subject == null) {
            boolean previousSuppression = suppressRefinementOperators;
            suppressRefinementOperators = true;
            try {
                subject = parseDelimitedSubject(true);
            } finally {
                suppressRefinementOperators = previousSuppression;
            }
        }

        if (!(match(OVER) || matchContextualIdentifier("over"))) {
            throw error(peek(), injectedSubject == null
                    ? "value match requires 'over' before its arms"
                    : "pipeline match requires 'over' and takes its subject from the left-hand value");
        }

        // Both outer delimiter styles are equivalent value matches.
        boolean braced = match(LBRACE);
        List<Ast.MatchValueArm> valueArms = new ArrayList<>();
        boolean seenDefault = false;
        boolean seenUnconditionalWildcard = false;

        while (!check(EOF) && !(braced ? check(RBRACE) : check(END))) {
            if (seenUnconditionalWildcard) {
                throw error(peek(), "unguarded wildcard match arm must be last");
            }
            boolean defaultArm = match(DEFAULT);
            if (!defaultArm) {
                if (seenDefault) throw error(peek(), "default match arm must be last");
                if (!matchContextualIdentifier("on") && !check(IS)) {
                    if (check(ELSE)) throw error(peek(), "match fallback is 'default ->', not else");
                    throw error(peek(), "match arm must start with 'on' or 'is'");
                }
                // Historical 'on default' is an input-only alias of final 'default'.
                defaultArm = match(DEFAULT);
            }
            if (defaultArm) {
                if (seenDefault) throw error(previous(), "duplicate default match arm");
                seenDefault = true;
            }

            // Normalize explicit Err() only for a syntactic trap Result;
            // arbitrary nominal constructors retain their declared arity.
            boolean discardTrapError = !defaultArm && subject instanceof Ast.TrapExpr
                    && check(IDENT) && peek().lexeme().equals("Err")
                    && checkNext(LPAREN) && current + 2 < tokens.size()
                    && tokens.get(current + 2).type() == RPAREN;
            Ast.Pattern pattern = defaultArm ? new Ast.WildcardPattern() : parsePattern();
            if (discardTrapError) {
                pattern = new Ast.ConstructorPattern("Err", List.of(new Ast.WildcardPattern()));
            }
            if (match(COLON)) {
                Ast.TypeRef armType = parseTypeRef();
                if (declaredResult == null || !declaredResult.equals(armType)) {
                    throw error(previous(), "match arm type must equal explicit match result type");
                }
            }
            Ast.Expr guard = null;
            if (matchWhenKeyword()) {
                if (defaultArm) throw error(previous(), "default match arm cannot have a guard");
                int previousGuardStart = matchGuardStartToken;
                matchGuardStartToken = current;
                try {
                    guard = parseExpression();
                } finally {
                    matchGuardStartToken = previousGuardStart;
                }
            }

            if (pattern instanceof Ast.WildcardPattern && guard == null) {
                seenUnconditionalWildcard = true;
            }

            if (check(FAT_ARROW)) {
                throw error(peek(),
                        "match implementations use the slim arrow '->'; '=>' is reserved for type definitions");
            }

            // Both outer delimiter styles use the same expression and block
            // arms. Retain the historical arrow-less braced block spelling.
            if (!match(ARROW) && !(braced && check(LBRACE))) {
                throw error(peek(), "value match arms use the slim arrow '->'");
            }

            if (match(DOUBLE_COLON)) {
                List<Ast.Stmt> body = parseUntil(END);
                consume(END, "expected 'end' to close value match block arm");
                if (body.isEmpty()
                        || !(body.getLast() instanceof Ast.ReturnStmt returned)
                        || returned.value() == null) {
                    throw error(previous(),
                            "value match block arm must end with explicit 'return value;'");
                }
                List<Ast.Stmt> prefix = body.subList(0, body.size() - 1);
                if (valueArmPrefixContainsReturn(prefix)) {
                    throw error(previous(),
                            "value match block arm v1 permits only its terminal top-level 'return value;' result");
                }
                valueArms.add(new Ast.MatchValueArm(
                        pattern, guard, List.copyOf(prefix), returned.value()));
                match(COMMA);
                continue;
            }

            if (check(LBRACE)) {
                // A block arm is independent of the outer match delimiter.
                List<Ast.Stmt> body = parseBlock();
                if (canonicalAttoSource) {
                    valueArms.add(canonicalBracedValueMatchArm(pattern, guard, body));
                } else {
                    if (body.size() != 1
                            || !(body.getFirst() instanceof Ast.ReturnStmt returned)
                            || returned.value() == null) {
                        throw error(previous(),
                                "legacy braced value match arms require exactly one 'return expression;'");
                    }
                    valueArms.add(new Ast.MatchValueArm(pattern, guard, List.of(), returned.value()));
                }
                match(COMMA);
                continue;
            }

            Ast.Expr value = parseExpression();
            // Arm delimiters are token-boundary based, never newline-sensitive.
            // Retain ';' for compatibility, but the canonical over/on/default/end
            // grammar also allows adjacent arms without redundant punctuation.
            // Do not let arbitrary expressions substitute for an arm terminator.
            if (!match(SEMICOLON, COMMA)
                    && !check(DEFAULT)
                    && !(braced ? check(RBRACE) : check(END))
                    && !(check(IDENT) && peek().lexeme().equals("on"))) {
                throw error(peek(), "value match arm requires ',', ';', 'on', 'default' or the match closing delimiter");
            }
            valueArms.add(new Ast.MatchValueArm(pattern, guard, value));
        }

        if (braced) consume(RBRACE, "expected '}' to close braced value match");
        else consume(END, "expected 'end' to close value match");

        if (valueArms.isEmpty()) {
            throw error(previous(), "value match requires at least one match arm");
        }
        return new Ast.MatchExpr(subject, valueArms, declaredResult,
                canonicalFirst || legacyFirst);
    }

    /**
     * Lower the verified subset directly to the existing lexical match block.
     * A terminal void exit is identical to falling through after cleanup.
     * Earlier/nested exits need a dedicated ownership CFG join and are rejected,
     * never silently interpreted as enclosing callable returns.
     */
    private List<Ast.Stmt> normalizeTerminalVoidMatchArm(List<Ast.Stmt> body) {
        List<Ast.Stmt> prefix = body;
        if (!body.isEmpty() && body.getLast() instanceof Ast.ReturnStmt returned) {
            if (returned.value() != null) {
                throw error(previous(), "do match arm must return void");
            }
            prefix = body.subList(0, body.size() - 1);
        }
        if (valueArmPrefixContainsReturn(prefix)) {
            throw error(previous(), "do match early or nested arm return requires ownership CFG lowering");
        }
        return List.copyOf(prefix);
    }

    private Ast.MatchValueArm canonicalBracedValueMatchArm(
            Ast.Pattern pattern, Ast.Expr guard, List<Ast.Stmt> body) {
        if (body.isEmpty()) throw error(previous(), "value match arm requires a result");
        Ast.Stmt terminal = body.getLast();
        Ast.Expr result;
        if (terminal instanceof Ast.ReturnStmt returned && returned.value() != null) {
            result = returned.value();
        } else if (terminal instanceof Ast.ExprStmt expression) {
            result = expression.expression();
        } else {
            throw error(previous(), "value match arm requires a terminal result expression");
        }
        List<Ast.Stmt> prefix = body.subList(0, body.size() - 1);
        if (valueArmPrefixContainsReturn(prefix)) {
            throw error(previous(), "value match block arm v1 permits only a terminal top-level result");
        }
        return new Ast.MatchValueArm(pattern, guard, List.copyOf(prefix), result);
    }

    /**
     * V1 block arms use one terminal top-level ARM_RESULT. Reject earlier
     * return statements even when nested in ordinary control flow; enabling
     * those requires a dedicated value-arm CFG completeness proof.
     *
     * Nested lambda/task expression bodies are not walked here because their
     * returns belong to their own callable/task control-flow domain.
     */
    private boolean valueArmPrefixContainsReturn(List<Ast.Stmt> body) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt) return true;
            if (stmt instanceof Ast.BlockStmt block
                    && valueArmPrefixContainsReturn(block.body())) return true;
            if (stmt instanceof Ast.TaskScopeStmt scope
                    && valueArmPrefixContainsReturn(scope.body())) return true;
            if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    if (valueArmPrefixContainsReturn(branch.body())) return true;
                }
                if (valueArmPrefixContainsReturn(conditional.elseBody())) return true;
            }
            if (stmt instanceof Ast.MatchStmt matched) {
                for (Ast.MatchArm arm : matched.arms()) {
                    if (valueArmPrefixContainsReturn(arm.body())) return true;
                }
            }
            if (stmt instanceof Ast.SwitchStmt switched) {
                for (Ast.SwitchCase arm : switched.cases()) {
                    if (valueArmPrefixContainsReturn(arm.body())) return true;
                }
                if (valueArmPrefixContainsReturn(switched.defaultBody())) return true;
            }
            if (stmt instanceof Ast.TryStmt attempted) {
                if (valueArmPrefixContainsReturn(attempted.body())
                        || valueArmPrefixContainsReturn(attempted.catchBody())
                        || valueArmPrefixContainsReturn(attempted.finallyBody())) return true;
            }
            if (stmt instanceof Ast.ForOfStmt loop
                    && valueArmPrefixContainsReturn(loop.body())) return true;
            if (stmt instanceof Ast.ForOfDestructureStmt loop
                    && valueArmPrefixContainsReturn(loop.body())) return true;
            if (stmt instanceof Ast.ForStmt loop
                    && valueArmPrefixContainsReturn(loop.body())) return true;
            if (stmt instanceof Ast.DoWhileStmt loop
                    && valueArmPrefixContainsReturn(loop.body())) return true;
            if (stmt instanceof Ast.LoopStmt loop
                    && valueArmPrefixContainsReturn(loop.body())) return true;
            if (stmt instanceof Ast.SelectStmt selected) {
                for (Ast.SelectArm arm : selected.arms()) {
                    if (valueArmPrefixContainsReturn(arm.body())) return true;
                }
            }
            if (stmt instanceof Ast.UsingBorrowStmt loan
                    && valueArmPrefixContainsReturn(loan.body())) return true;
            if (stmt instanceof Ast.UsingTelemetrySpanStmt scope
                    && valueArmPrefixContainsReturn(scope.body())) return true;
        }
        return false;
    }

    /**
     * Field patterns never allocate, inspect host dictionaries, or convert a
     * nominal object into a record. Static type/ownership proof is mandatory
     * before the existing pattern dispatcher evaluates any field.
     */
    private Ast.RecordPattern parseTildeRecordPattern(boolean structural) {
        consume(LPAREN, "record field patterns require '~(field: Pattern, ...)'");
        java.util.List<Ast.RecordFieldPattern> fields = new java.util.ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        if (check(RPAREN)) {
            throw error(peek(), "empty record field pattern does not constrain a value");
        }
        do {
            if (check(ELLIPSIS)) {
                throw error(peek(), "record pattern spread requires separate ownership proof");
            }
            String name = consumeStaticObjectKeyName("expected static record pattern field");
            if (!names.add(name)) {
                throw error(previous(), "duplicate record field pattern '" + name + "'");
            }
            consume(COLON, "record patterns use ':', never computed map '=>'");
            fields.add(new Ast.RecordFieldPattern(name, parsePattern()));
            if (fields.size() > 64) {
                throw error(previous(), "record field pattern exceeds 64 fields");
            }
            if (!match(COMMA)) break;
        } while (!check(RPAREN));
        consume(RPAREN, "expected ')' to close record field pattern");
        return new Ast.RecordPattern(structural, fields);
    }

    /**
     * Positional byte/bit prefixes in match position only. A sequence element
     * is a decimal literal, a fresh capture, or '_'; tail is always final.
     * This is deliberately separate from binary { field: bytes[length] }.
     */
    private Ast.SequencePattern parseByteBitSequencePattern() {
        Token head = advance();
        boolean bytes = head.lexeme().equals("bytes");
        consume(LBRACKET, "expected '[' after byte/bit pattern name");
        List<Ast.SequenceElement> elements = new ArrayList<>();
        String tail = null;
        while (!check(RBRACKET) && !check(EOF)) {
            if (match(PIPE)) {
                if (elements.isEmpty()) {
                    throw error(previous(), "sequence tail requires a nonempty prefix");
                }
                tail = consume(IDENT, "expected final sequence tail name").lexeme();
                if (!check(RBRACKET)) {
                    throw error(peek(), "sequence pattern tail must be final");
                }
                break;
            }
            if (elements.size() == 256) {
                throw error(peek(), "sequence pattern exceeds 256 elements");
            }
            if (match(INT)) {
                long value = parseSignedI64(previous());
                if (value < 0 || value > (bytes ? 255 : 1)) {
                    throw error(previous(), bytes
                            ? "byte pattern literal must be 0..255"
                            : "bit pattern literal must be 0 or 1");
                }
                elements.add(new Ast.SequenceElement((int) value, null));
            } else if (match(MINUS)) {
                throw error(previous(), "negative byte/bit pattern literal is invalid");
            } else if (check(IDENT)) {
                elements.add(new Ast.SequenceElement(null, advance().lexeme()));
            } else {
                throw error(peek(), "expected byte/bit literal, binding, or final tail");
            }
            if (check(RBRACKET)) break;
            if (match(COMMA)) {
                if (check(RBRACKET)) throw error(peek(), "trailing comma in sequence pattern");
                continue;
            }
            if (check(PIPE)) continue;
            throw error(peek(), "expected ',' or '|' in sequence pattern");
        }
        consume(RBRACKET, "expected ']' after byte/bit sequence pattern");
        return new Ast.SequencePattern(
                bytes ? Ast.SequenceUnit.BYTE : Ast.SequenceUnit.BIT, elements, tail);
    }

    private Ast.Pattern parsePattern() {
        if (match(BANG)) {
            // A negative type pattern is a complement of the runtime-reifiable
            // type test, not a cast to the excluded type.
            Ast.TypeRef negative = Ast.TypeRef.complement(parseTypeAtom());
            String binding = match(AS) ? consume(IDENT, "expected name after 'as'").lexeme() : null;
            return new Ast.TypePattern(negative, binding);
        }
        if (match(STRUCTURAL) || matchContextualIdentifier("structural")) {
            if (match(TILDE)) {
                if (isContextualIdentifier("record") && checkNext(LPAREN)) {
                    advance(); // explicit ~record field-pattern head
                }
                return parseTildeRecordPattern(true);
            }
            Ast.TypeRef target = parseTypeRef();
            // Explicit 'as' is the canonical pattern binder. Preserve the
            // historical positional binder for existing structural TypeName code.
            String binding = match(AS)
                    ? consume(IDENT, "expected structural match binding name after 'as'").lexeme()
                    : check(IDENT) && !peek().lexeme().equals("_")
                            ? advance().lexeme()
                            : null;
            return new Ast.StructuralPattern(target, binding);
        }

        if (match(TILDE)) {
            // '~ {field: Type, ...} as name' is the short spelling of
            // 'structural {field: Type, ...} as name'.
            if (check(LBRACE)) {
                Ast.TypeRef shape = parseTypeRef();
                String binding = match(AS)
                        ? consume(IDENT, "expected structural match binding name after 'as'").lexeme()
                        : null;
                return new Ast.StructuralPattern(shape, binding);
            }
            // Bare "on ~Point" is a structural VALUE pattern, not the same
            // operation as the type test "on is ~Point". Until the value-
            // intersection contract has lowering, do not silently treat the
            // two forms as equivalent or steal the nominal TypePattern path.
            if (isContextualIdentifier("record") && checkNext(LPAREN)) {
                throw error(peek(), "on ~record(...) is retired; use an explicit structural field pattern");
            }
            if (!check(LPAREN)) {
                throw error(peek(), "on ~Type is a structural value pattern requiring checked intersection lowering; use 'on is ~Type' for a structural type test");
            }
            return parseTildeRecordPattern(false);
        }

        if (match(IS)) {
            // "is ~Point" is a structural TYPE test (not a collection factory
            // and not the value-equality/destructuring "on ~Point" form).
            // Keep the existing structural checker and runtime semantics:
            // do not erase the sigil into a nominal TypePattern.
            boolean structuralTest = match(TILDE)
                    || match(STRUCTURAL)
                    || matchContextualIdentifier("structural");
            Ast.TypeRef target = parseTypeRef();
            String binding = null;
            if (match(AS)) {
                // Pattern bindings are currently readonly VAL in both
                // StructuralPattern and TypePattern; do not accept "as let"
                // or "as const" and silently discard its distinct semantics.
                if (match(LET, CONST))
                    throw error(previous(), "type pattern binding qualifier requires 'val' in this backend");
                match(VAL);
                binding = consume(IDENT, "expected bound name after type pattern 'as val'").lexeme();
            } else if (check(IDENT) && !peek().lexeme().equals("_")) {
                binding = advance().lexeme();
            }
            return structuralTest
                    ? new Ast.StructuralPattern(target, binding)
                    : new Ast.TypePattern(target, binding);
        }

        if (match(LPAREN)) {
            List<Ast.Pattern> elements = new ArrayList<>();
            elements.add(parsePattern());
            consume(COMMA, "tuple match patterns require at least two elements");
            do elements.add(parsePattern()); while (match(COMMA));
            consume(RPAREN, "expected ')' after tuple match pattern");
            return new Ast.TuplePattern(elements);
        }

        if (check(IDENT) && checkNext(LBRACKET)
                && (peek().lexeme().equals("bytes") || peek().lexeme().equals("bits"))) {
            return parseByteBitSequencePattern();
        }
        if (match(INT)) return new Ast.LiteralPattern(parseSignedI64(previous()));
        if (match(FLOAT)) return new Ast.LiteralPattern(Double.parseDouble(previous().lexeme().replace("_", "")));
        if (match(STRING)) return new Ast.LiteralPattern(previous().lexeme());
        if (match(SYMBOL_LITERAL)) return new Ast.LiteralPattern(dev.oreslang.runtime.OresSymbol.process(previous().lexeme()));
        if (match(TRUE)) return new Ast.LiteralPattern(Boolean.TRUE);
        if (match(FALSE)) return new Ast.LiteralPattern(Boolean.FALSE);
        if (check(IDENT) && peek().lexeme().equals("_")) {
            advance();
            return new Ast.WildcardPattern();
        }

        // Canonical nominal binding syntax is `Type name`.
        // Parse it speculatively so constructor patterns such as Some(value)
        // and ordinary catch-all bindings remain unambiguous.
        if (check(IDENT)) {
            int patternStart = current;
            try {
                Ast.TypeRef target = parseTypeRef();
                if (match(AS)) {
                    return new Ast.TypePattern(
                            target, consume(IDENT, "expected binding name after 'as'").lexeme());
                }
                if (check(IDENT) && !peek().lexeme().equals("_")
                        && !peek().lexeme().equals("when")) {
                    // 'when' starts an arm guard, never a typed binding name.
                    String binding = advance().lexeme();
                    return new Ast.TypePattern(target, binding);
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to constructor/binding parsing from the same token.
            }
            current = patternStart;
        }

        // Qualified variant bindings are retained as *nominal* constructor
        // patterns in the AST. Backends without enum-payload lowering reject
        // them at the checker rather than silently treating a variant as a
        // catch-all type or erasing its payload.
        if (check(IDENT) && checkNext(DOT)) {
            StringBuilder variant = new StringBuilder(advance().lexeme());
            do {
                consume(DOT, "expected '.' in qualified match variant");
                variant.append('.').append(consume(IDENT,
                        "expected variant member after '.'").lexeme());
            } while (check(DOT));
            consume(AS, "qualified variant destructuring requires 'as ( ... )'");
            consume(LPAREN, "expected '(' after variant 'as'");
            List<Ast.Pattern> fields = new ArrayList<>();
            if (!check(RPAREN)) {
                do {
                    fields.add(new Ast.BindingPattern(consume(IDENT,
                            "expected binding name in variant payload").lexeme()));
                } while (match(COMMA));
            }
            consume(RPAREN, "expected ')' after variant payload bindings");
            return new Ast.ConstructorPattern(variant.toString(), fields);
        }

        if (check(IDENT)) {
            String name = advance().lexeme();
            if (match(LPAREN)) {
                List<Ast.Pattern> args = new ArrayList<>();
                if (!check(RPAREN)) {
                    do args.add(parsePattern()); while (match(COMMA));
                }
                consume(RPAREN, "expected ')' after constructor pattern");
                return new Ast.ConstructorPattern(name, args);
            }
            // Upper-case bare names are zero-arity constructors; lower-case names bind.
            if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                return new Ast.ConstructorPattern(name, List.of());
            }
            return new Ast.BindingPattern(name);
        }

        if (check(LBRACKET)) {
            throw error(peek(), "list patterns are not supported; use tuples for fixed structural matching");
        }
        throw error(peek(), "expected match pattern");
    }

    private Ast.SwitchStmt parseSwitch() {
        boolean delimited = check(PIPE);
        Ast.Expr subject = parseDelimitedSubject(false);
        if (delimited && !(match(OVER) || matchContextualIdentifier("over"))) {
            throw error(peek(), "delimited switch requires 'over'");
        }
        match(SEMICOLON);
        List<Ast.SwitchCase> cases = new ArrayList<>();
        List<Ast.Stmt> defaultBody = List.of();
        boolean sawDefault = false;

        while (!check(END) && !check(EOF)) {
            if (matchContextualKeyword("case")) {
                if (sawDefault) throw error(previous(), "switch case cannot appear after default");
                List<Ast.Expr> constants = new ArrayList<>();
                do constants.add(parseExpression()); while (match(COMMA));
                if (check(FAT_ARROW)) {
                    throw error(peek(), "switch implementations use the slim arrow '->'; '=>' is reserved for type definitions");
                }
                consume(ARROW, "switch cases use the slim arrow '->'");
                cases.add(new Ast.SwitchCase(constants, parseBlock()));
                continue;
            }
            if (match(DEFAULT)) {
                if (sawDefault) throw error(previous(), "switch can contain only one default arm");
                sawDefault = true;
                if (check(FAT_ARROW)) {
                    throw error(peek(), "switch implementations use the slim arrow '->'; '=>' is reserved for type definitions");
                }
                consume(ARROW, "switch default uses the slim arrow '->'");
                defaultBody = parseBlock();
                continue;
            }
            throw error(peek(), "expected 'case', 'default', or 'end' in switch");
        }

        consume(END, "expected 'end' to close switch");
        if (delimited) match(SEMICOLON);
        return new Ast.SwitchStmt(subject, cases, defaultBody);
    }

    /**
     * Both "try { ... } catch (...) { ... }" and
     * "try ... catch (...) ... finally ... end" lower to the same TryStmt.
     * The opening delimiter selects the style for every arm; a keyword try
     * owns exactly one END, and braced tries must not consume an END.
     */
    private Ast.TryStmt parseTry() {
        boolean braced = check(LBRACE);
        List<Ast.Stmt> body = braced ? parseBlock() : parseTryKeywordBody(CATCH, FINALLY, END);
        if (!check(CATCH) && !check(FINALLY)) {
            throw error(peek(), "expected catch or finally after try block");
        }

        String errorName = null;
        List<Ast.Stmt> catchBody = List.of();
        if (match(CATCH)) {
            consume(LPAREN, "expected '(' after catch");
            if (check(RPAREN)) {
                // An unspellable internal binder ensures catch() never makes
                // an exception variable visible to user code.
                errorName = "$catch_ignored";
            } else {
                Token first = consume(IDENT, "expected catch binding or Error type");
                if (check(IDENT)) {
                    if (!first.lexeme().equals("Error")) {
                        throw error(first, "typed catch currently requires Error");
                    }
                    errorName = consume(IDENT, "expected name after catch Error").lexeme();
                } else {
                    errorName = first.lexeme();
                }
            }
            consume(RPAREN, "expected ')' after catch binding");
            if (!braced && check(LBRACE)) {
                throw error(peek(), "keyword try catch body cannot use braces; use end");
            }
            catchBody = braced ? parseBlock() : parseTryKeywordBody(FINALLY, END);
            if (!braced && check(CATCH)) throw error(peek(), "at most one catch clause is allowed");
        }

        List<Ast.Stmt> finallyBody = List.of();
        if (match(FINALLY)) {
            if (!braced && check(LBRACE)) {
                throw error(peek(), "keyword try finally body cannot use braces; use end");
            }
            finallyBody = braced ? parseBlock() : parseTryKeywordBody(END);
            if (!braced && (check(CATCH) || check(FINALLY))) {
                throw error(peek(), "catch must precede finally; at most one finally is allowed");
            }
        }
        if (!braced) consume(END, "expected 'end' to close keyword try");
        return new Ast.TryStmt(body, errorName, catchBody, finallyBody);
    }

    private List<Ast.Stmt> parseTryKeywordBody(Token.Type... stops) {
        localTypeScopes.push(new java.util.HashSet<>());
        try {
            return parseUntil(stops);
        } finally {
            localTypeScopes.pop();
        }
    }

    private List<Ast.Stmt> parseUntil(Token.Type... terminators) {
        List<Ast.Stmt> body = new ArrayList<>();
        outer: while (!check(EOF)) {
            for (Token.Type terminator : terminators) if (check(terminator)) break outer;
            body.add(parseStatement());
        }
        return body;
    }

    public Ast.Expr parseExpression() { return parseAssignment(); }

    private Ast.Expr parseAssignment() {
        Ast.Expr expr = parsePipeline();
        rejectUnsupportedActorPipeline();
        if (match(EQUAL)) {
            if (!(expr instanceof Ast.NameExpr) && !(expr instanceof Ast.MemberExpr) && !(expr instanceof Ast.IndexExpr)) {
                throw error(previous(), "assignment target must be a local, field, or index");
            }
            return new Ast.AssignExpr(expr, parseAssignment());
        }
        return expr;
    }

    /**
     * Value pipelines currently execute as eager calls, but retain their
     * syntax provenance for future typed lifting. Do not infer a pipeline
     * from an ordinary stage(Future) call. The leading value is evaluated
     * once as the first argument. Namespace combinators
     * (Option.map, Result.map_err, etc.) lower to receiver members so that
     * the normal sum-type checker and ownership rules apply unchanged.
     */
    private Ast.Expr parsePipeline() {
        Ast.Expr value = parseConditional();
        int stages = 0;
        while (true) {
            rejectUnsupportedActorPipeline();
            if (!match(PIPE_FORWARD)) break;
            Token operator = previous();
            if (++stages > 128) {
                throw error(operator, "pipeline exceeds 128 stages");
            }
            // Subject-elided conditional stages share the ordinary checked
            // expression nodes. Do not substitute or repeat the receiver.
            if (canonicalAttoSource && match(IF, IFNOT, UNLESS)) {
                Token.Type introducer = previous().type();
                Ast.Expr predicate = parsePipelinePredicate(value);
                if (introducer != IF) predicate = new Ast.UnaryExpr("!", predicate);
                consume(THEN, "pipeline if requires 'then' after its receiver predicate");
                value = parseValueIfElse(predicate, parseConditionalValueArm());
                continue;
            }
            if (canonicalAttoSource && (check(QUESTION) || pipelinePredicateStarts())) {
                value = parsePipelinePredicate(value);
                if (match(QUESTION)) {
                    Ast.Expr yes = check(LBRACE)
                            ? parseSingleResultTernaryBlock() : parseAssignment();
                    consume(COLON, "expected ':' in pipeline ternary");
                    Ast.Expr no = check(LBRACE)
                            ? parseSingleResultTernaryBlock() : parseConditional();
                    value = new Ast.ConditionalExpr(value, yes, no);
                }
                continue;
            }
            // A match stage consumes the accumulated expression as its subject.
            // Reuse the normal match AST, including its type and ownership checks.
            if (match(MATCH)) {
                value = parseValueMatch(value);
                continue;
            }
            // Bare await is the ordinary prefix operator with its operand
            // supplied by the accumulated pipeline value, not std.await.
            if (match(AWAIT)) {
                if (check(LPAREN) || check(LT)) {
                    throw error(peek(), "pipeline await takes only the left-hand value");
                }
                value = new Ast.AwaitExpr(value);
                continue;
            }
            // std.await is a *unary*, compiler-owned suspension stage, not a
            // callable value, factory, or Future.map/flat_map. Reuse the same
            // checked AwaitExpr as the prefix keyword. Its origin flag lets
            // the checker reject shadowing of the reserved core namespace.
            if (isContextualIdentifier("std") && checkNext(DOT)
                    && current + 2 < tokens.size()
                    && tokens.get(current + 2).type() == AWAIT) {
                advance(); // std
                advance(); // .
                advance(); // await
                if (check(LPAREN) || check(LT)) {
                    throw error(peek(), "std.await is a unary pipeline adapter, not a callable factory");
                }
                value = new Ast.AwaitExpr(value, true);
                continue;
            }
            // A bare lambda stage uses the existing lambda AST. Parse its
            // expression body below pipeline precedence so the next top-level
            // |> starts the NEXT stage, not part of this lambda body.
            // Parentheses allow a nested pipeline in the lambda expression.
            Ast.Expr stage;
            if (check(PIPE)) {
                stage = parsePipeLambda(false, true);
            } else if (check(NLEX) && checkNext(PIPE)) {
                advance();
                stage = parsePipeLambda(true, true);
            } else {
                stage = parsePostfix();
            }
            if (stage instanceof Ast.CallExpr call) {
                if (call.callee() instanceof Ast.MemberExpr member
                        && member.receiver() instanceof Ast.NameExpr namespace
                        && (namespace.name().equals("Option") || namespace.name().equals("Result"))) {
                    value = new Ast.CallExpr(new Ast.MemberExpr(value, member.member()),
                            call.typeArguments(), call.typeArgumentsPresent(),
                            call.arguments(), Ast.CallOrigin.VALUE_PIPE_RECEIVER);
                } else if (isPipelineCallable(call.callee())) {
                    List<Ast.Expr> args = new ArrayList<>();
                    args.add(value);
                    args.addAll(call.arguments());
                    value = new Ast.CallExpr(call.callee(), call.typeArguments(),
                            call.typeArgumentsPresent(), args, Ast.CallOrigin.VALUE_PIPE_ARGUMENT);
                } else {
                    throw error(operator, "pipeline stage must call a named function or Option/Result combinator");
                }
            } else if (isPipelineCallable(stage)) {
                value = new Ast.CallExpr(stage, List.of(), false, List.of(value),
                        Ast.CallOrigin.VALUE_PIPE_ARGUMENT);
            } else {
                throw error(operator, "pipeline stage must be a named callable");
            }
        }
        if (check(PIPE_REVERSE) && stages > 0) {
            throw error(peek(), "E_PIPE_DIRECTION: mixing '|>' and '<|' without parentheses is ambiguous");
        }
        // Reverse functional application is right-associative:
        // f <| g <| value == f(g(value)). Unlike 'rpipe', this is one-value
        // callable composition, not a continuous actor/stream connection.
        if (match(PIPE_REVERSE)) {
            List<Ast.Expr> reverseStages = new ArrayList<>();
            Token operator = previous();
            reverseStages.add(value);
            while (true) {
                if (reverseStages.size() > 128) {
                    throw error(operator, "reverse functional pipeline exceeds 128 stages");
                }
                Ast.Expr right = parseConditional();
                if (match(PIPE_REVERSE)) {
                    reverseStages.add(right);
                    operator = previous();
                    continue;
                }
                if (check(PIPE_FORWARD)) {
                    throw error(peek(), "E_PIPE_DIRECTION: mixing '<|' and '|>' without parentheses is ambiguous");
                }
                for (int i = reverseStages.size() - 1; i >= 0; i--) {
                    Ast.Expr callable = reverseStages.get(i);
                    if (callable instanceof Ast.CallExpr call) {
                        // Exact forward-pipe Option/Result receiver lowering:
                        // preserve the tagged-sum value and the original
                        // typechecker, ownership and exception effect gates.
                        // Never auto-unwrap Err or None while reversing syntax.
                        if (call.callee() instanceof Ast.MemberExpr member
                                && member.receiver() instanceof Ast.NameExpr namespace
                                && (namespace.name().equals("Option")
                                    || namespace.name().equals("Result"))) {
                            right = new Ast.CallExpr(new Ast.MemberExpr(right, member.member()),
                                    call.typeArguments(), call.typeArgumentsPresent(),
                                    call.arguments(), Ast.CallOrigin.VALUE_PIPE_RECEIVER);
                        } else if (isPipelineCallable(call.callee())) {
                            List<Ast.Expr> args = new ArrayList<>();
                            args.add(right);
                            args.addAll(call.arguments());
                            right = new Ast.CallExpr(call.callee(), call.typeArguments(),
                                    call.typeArgumentsPresent(), args, Ast.CallOrigin.VALUE_PIPE_ARGUMENT);
                        } else {
                            throw error(operator, "reverse pipeline requires a named callable or Option/Result combinator");
                        }
                    } else if (isPipelineCallable(callable)) {
                        right = new Ast.CallExpr(callable, List.of(), false, List.of(right),
                                Ast.CallOrigin.VALUE_PIPE_ARGUMENT);
                    } else {
                        throw error(operator, "reverse pipeline stage must be a named callable");
                    }
                }
                value = right;
                break;
            }
        }
        return value;
    }

    private void rejectUnsupportedActorPipeline() {
        if (!isContextualIdentifier("pipe") && !isContextualIdentifier("rpipe")) return;
        Token operator = advance();
        consumeActorOperatorBang(operator, "E_PIPE_BANG_BOUNDARY");
        throw error(operator, "E_PIPE_BACKEND_UNSUPPORTED: continuous actor pipeline 'pipe' "
                + "flows left-to-right and 'rpipe' flows right-to-left; typed ports, bounded "
                + "transport, checked completion and supervisor lowering are required");
    }

    private void consumeActorOperatorBang(Token operator, String diagnostic) {
        if (!check(BANG) || !adjacent(operator, peek())) return;
        Token bang = advance();
        Token suffix = peek();
        if (!suffix.lexeme().isEmpty() && adjacent(bang, suffix)) {
            int first = suffix.lexeme().codePointAt(0);
            if (Character.isLetterOrDigit(first) || first == '_' || first == '!') {
                throw error(bang, diagnostic + ": '" + operator.lexeme()
                        + "!' requires a token boundary");
            }
        }
    }

    private boolean pipelinePredicateStarts() {
        return check(IS) || check(LT) || check(LTE) || check(GT) || check(GTE)
                || check(EQ) || check(EQUAL_EQUAL) || check(NEQ) || check(BANG_EQUAL);
    }

    private Ast.Expr parsePipelinePredicate(Ast.Expr receiver) {
        if (match(IS)) {
            // In receiver position only, 'is int' is short for 'is type int'.
            // Parse a type atom, not a postfix optional type: the next '?' is
            // the ternary operator. Compound types can be parenthesized.
            match(TYPE);
            Ast.TypeRef target = parseTypeAtom();
            return new Ast.TypeTestExpr(receiver, target, null);
        }
        if (match(LT, LTE, GT, GTE)) {
            String op = previous().lexeme();
            return new Ast.BinaryExpr(op, receiver, parseShift());
        }
        if (match(EQ, EQUAL_EQUAL)) return new Ast.BinaryExpr("eq", receiver, parseComparison());
        if (match(NEQ, BANG_EQUAL)) return new Ast.BinaryExpr("neq", receiver, parseComparison());
        return receiver;
    }

    private static boolean isPipelineCallable(Ast.Expr stage) {
        return stage instanceof Ast.NameExpr || stage instanceof Ast.LambdaExpr;
    }

    /** Canonical value if shares the checked conditional-expression AST. */
    private Ast.Expr parseValueIf(Token.Type introducer) {
        Ast.Expr predicate = parseCondition();
        Ast.Expr condition = introducer == IF ? predicate : new Ast.UnaryExpr("!", predicate);
        consume(THEN, "value-producing 'if' requires 'then'");
        return parseValueIfElse(condition, parseConditionalValueArm());
    }

    private Ast.Expr parseValueIfElse(Ast.Expr condition, Ast.Expr whenTrue) {
        List<Ast.Expr> predicates = new ArrayList<>();
        List<Ast.Expr> values = new ArrayList<>();
        predicates.add(condition);
        values.add(whenTrue);
        while (match(ELSEIF)) {
            if (predicates.size() >= 127) {
                throw error(previous(), "E_VALUE_ARM: value if exceeds 128 total arms");
            }
            predicates.add(parseCondition());
            consume(THEN, "value-producing 'elif' requires 'then'");
            values.add(parseConditionalValueArm());
        }
        consume(ELSE, "value-producing 'if' requires an 'else' value; use 'do if' for statements");
        Ast.Expr result = parseConditionalValueArm();
        consume(FI, "expected 'fi' to close value-producing 'if'");
        for (int i = predicates.size() - 1; i >= 0; i--) {
            result = new Ast.ConditionalExpr(predicates.get(i), values.get(i), result);
        }
        return result;
    }

    private Ast.Expr parseConditionalValueArm() {
        boolean explicitResult = match(RETURN, CARET);
        if (check(BREAK) || check(CONTINUE) || check(DO) || check(SEMICOLON)
                || check(VOID) || check(FI) || check(ELSE)) {
            throw error(peek(), "value-producing 'if' branch must produce a value; use 'do if' for imperative control flow");
        }
        Ast.Expr value = parseExpression();
        if (explicitResult) match(SEMICOLON);
        return value;
    }

    private Ast.Expr parseConditional() {
        Ast.Expr condition = parseLogicalOr();
        if (!match(QUESTION)) return condition;
        Ast.Expr whenTrue = canonicalAttoSource && check(LBRACE)
                ? parseSingleResultTernaryBlock() : parseAssignment();
        consume(COLON, "expected ':' in ternary expression");
        Ast.Expr whenFalse = canonicalAttoSource && check(LBRACE)
                ? parseSingleResultTernaryBlock() : parseConditional();
        return new Ast.ConditionalExpr(condition, whenTrue, whenFalse);
    }

    // A single explicit result needs no lexical bindings or closure. General
    // statement sequences remain unsupported until lexical block lowering.
    private Ast.Expr parseSingleResultTernaryBlock() {
        consume(LBRACE, "expected ternary arm block");
        if (check(RBRACE)) {
            throw error(peek(), "E_VALUE_ARM: empty ternary block requires a value");
        }
        if (!match(RETURN, CARET)) {
            throw error(peek(), "E_VALUE_ARM: ternary block requires explicit return or ^; "
                    + "multi-statement arms require lexical value-block lowering");
        }
        Ast.Expr result = parseExpression();
        match(SEMICOLON);
        consume(RBRACE, "E_VALUE_ARM: single-result ternary block requires closing '}'");
        return result;
    }

    private Ast.Expr parseLogicalOr() {
        Ast.Expr expr = parseLogicalXor();
        while (current != delimitedSubjectEnd && !isChannelCallbackDelimiter() && matchAdjacentPair(PIPE)) {
            expr = new Ast.BinaryExpr("||", expr, parseLogicalXor());
        }
        return expr;
    }

    private boolean isChannelCallbackDelimiter() {
        return current + 2 < tokens.size()
                && tokens.get(current).type() == PIPE
                && tokens.get(current + 1).type() == PIPE
                && tokens.get(current + 2).type() == ARROW
                && adjacent(tokens.get(current), tokens.get(current + 1));
    }

    private Ast.Expr parseLogicalXor() {
        Ast.Expr expr = parseLogicalAnd();
        // Canonical boolean XOR: both operands are evaluated once, left to right.
        // Only an adjacent double caret is recognized; single '^' stays reserved.
        while (matchAdjacentPair(CARET)) {
            expr = new Ast.BinaryExpr("^^", expr, parseLogicalAnd());
        }
        return expr;
    }

    private Ast.Expr parseLogicalAnd() {
        Ast.Expr expr = parseBitwiseOr();
        while (matchAdjacentPair(AMP)) expr = new Ast.BinaryExpr("&&", expr, parseBitwiseOr());
        return expr;
    }

    private Ast.Expr parseBitwiseOr() {
        Ast.Expr expr = parseBitwiseXor();
        while (current != delimitedSubjectEnd && !isChannelCallbackDelimiter() && matchSingleOperator(PIPE)) {
            Token operator = previous();
            Ast.Expr right = parseBitwiseXor();
            // Distinct from legacy integer/conditional bitwise OR and from
            // functional |>; until v3 actor graph lowering is executable,
            // symbolic spawn-to-spawn pipes must fail at the compiler
            // boundary, not become an untyped scalar BinaryExpr.
            if (isActorSpawnExpression(expr) || isActorSpawnExpression(right)) {
                throw error(operator, "actor pipeline '|' requires typed emit, "
                        + "owned actor graph and bounded mailbox lowering; not yet enabled");
            }
            expr = new Ast.BinaryExpr("|", expr, right);
        }
        return expr;
    }

    private static boolean isActorSpawnExpression(Ast.Expr value) {
        return value instanceof Ast.RuntimeCallExpr call
                && call.operation().equals("spawn");
    }

    private Ast.Expr parseBitwiseXor() {
        Ast.Expr expr = parseBitwiseAnd();
        if (matchSingleOperator(CARET)) throw error(previous(),
                "'^' is reserved and is not AttoLang source syntax; use Integer.bitXor(a, b)");
        return expr;
    }

    private Ast.Expr parseBitwiseAnd() {
        Ast.Expr expr = parseEquality();
        while (matchSingleOperator(AMP)) expr = new Ast.BinaryExpr("&", expr, parseEquality());
        return expr;
    }

    private Ast.Expr parseEquality() {
        Ast.Expr expr = parseComparison();
        while (true) {
            String op;
            if (match(EQ, EQUAL_EQUAL)) op = "eq";
            else if (match(NEQ, BANG_EQUAL)) op = "neq";
            else if (matchAdjacentBangEqKeyword()) op = "neq";
            else if (!suppressRefinementOperators && match(IS)) op = "is";
            else break;
            expr = new Ast.BinaryExpr(op, expr, parseComparison());
        }
        return expr;
    }

    private Ast.Expr parseComparison() {
        Ast.Expr expr = parseShift();
        while (true) {
            if (checkIoReadBinder()) break; // `source >> val name;`, not `>` twice
            if (match(LT, LTE, GT, GTE)) {
                String op = previous().lexeme();
                expr = new Ast.BinaryExpr(op, expr, parseShift());
                continue;
            }
            if (!suppressRefinementOperators && check(IS) && checkNext(TYPE)) {
                advance();
                consume(TYPE, "expected 'type' after 'is' in a nominal type test");
                Ast.TypeRef target = parseTypeRef();
                String binding = check(IDENT) && !peek().lexeme().equals("_") ? advance().lexeme() : null;
                expr = new Ast.TypeTestExpr(expr, target, binding);
                continue;
            }
            if (!suppressRefinementOperators && match(MATCHES)) {
                expr = new Ast.PatternTestExpr(expr, parsePattern());
                continue;
            }
            // In a condition head, `as let name when ...` ends the value.
            // Other `as T` uses retain ordinary checked-cast precedence.
            if (check(AS) && checkNext(LET)) {
                // Keep the earlier whole-head `as let name when predicate`
                // spelling; otherwise this is a value-preserving capture.
                if (current + 3 < tokens.size()
                        && tokens.get(current + 3).lexeme().equals("when")) break;
                advance();
                advance();
                String name = consume(IDENT, "capture requires a binding name").lexeme();
                expr = new Ast.CaptureExpr(expr, name);
                continue;
            }
            if (!suppressRefinementOperators && match(AS)) {
                boolean optional = match(QUESTION);
                expr = new Ast.CastExpr(expr, parseTypeRef(),
                        optional ? Ast.CastMode.OPTIONAL : Ast.CastMode.CHECKED);
                continue;
            }
            break;
        }
        return expr;
    }

    private Ast.Expr parseShift() {
        Ast.Expr expr = parseAdditive();
        while (true) {
            String op;
            if (matchAdjacentTriple(GT)) op = ">>>";
            else if (matchAdjacentTriple(LT)) op = "<<<";
            // Arithmetic (sign-filling) right shift is the contextual infix word
            // `ashr` (atto-spec bitwise-and-stream-operators-v1).
            else if (isContextualIdentifier("ashr")) { advance(); op = "ashr"; }
            // `source >> val name;` belongs to the enclosing statement.
            else if (checkIoReadBinder()) break;
            // `<<` and `>>` are channel I/O (Ast.IoShorthand), never shifts: the
            // statement parser desugars them and the checker rejects any other use.
            else if (matchAdjacentPair(LT)) op = "<<";
            else if (matchAdjacentPair(GT)) op = ">>";
            else break;
            expr = new Ast.BinaryExpr(op, expr, parseAdditive());
        }
        return expr;
    }

    private Ast.Expr parseAdditive() {
        Ast.Expr expr = parseMultiplicative();
        while (match(PLUS, MINUS)) {
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseMultiplicative());
        }
        return expr;
    }

    private Ast.Expr parseMultiplicative() {
        Ast.Expr expr = parseWithUpdate();
        while (match(STAR, SLASH, PERCENT)) {
            if (previous().type() == PERCENT) {
                throw error(previous(), "noncanonical '%' source operator; use named Integer.rem/mod");
            }
            if (previous().type() == PERCENT) throw error(previous(),
                    "'%' is reserved and is not AttoLang source syntax; use Integer.rem(a, b) or Integer.mod(a, b)");
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseWithUpdate());
        }
        return expr;
    }

    /**
     * Immutable updates bind after unary rt copy/take, not inside the rt
     * operation. Thus 'rt copy p with {...}' parses as (rt copy p) with {...}.
     * Keep 'with' contextual so existing names remain valid without an update.
     */
    private Ast.Expr parseWithUpdate() {
        Ast.Expr expr = parseUnary();
        while (isContextualIdentifier("with") && checkNext(LBRACE)) {
            advance();
            consume(LBRACE, "expected '{' after with");
            List<Ast.WithField> changes = new ArrayList<>();
            if (check(RBRACE)) throw error(peek(), "with update requires at least one field");
            do {
                Token field = consume(IDENT, "expected field name in with update");
                consume(FAT_ARROW, "expected '=>' after with field name");
                changes.add(new Ast.WithField(field.lexeme(), parseExpression()));
            } while (match(COMMA) && !check(RBRACE));
            consume(RBRACE, "expected '}' after with fields");
            expr = new Ast.WithExpr(expr, changes);
        }
        return expr;
    }

    /** Detect removed collection-factory spellings only in expression
     * context; structural type and match parsing have separate entry paths.
     */
    private boolean isTildeAggregateConstructorHead() {
        if (!check(TILDE) || current + 2 >= tokens.size()
                || tokens.get(current + 1).type() != IDENT) return false;
        Token tilde = tokens.get(current);
        Token kind = tokens.get(current + 1);
        Token next = tokens.get(current + 2);
        // Whitespace is insignificant for a removed constructor call:
        // reject both ~tuple(...) and ~ tuple (...). A spaced '<' still
        // parses as comparison rather than a former generic witness.
        Token.Type suffix = next.type();
        if (suffix != LPAREN && (suffix != LT || !adjacent(kind, next))) return false;
        return switch (tokens.get(current + 1).lexeme()) {
            case "array", "tuple", "vector", "fixed_array", "fixed_list",
                    "list", "slice", "set", "map", "entry", "pair", "record" -> true;
            default -> false;
        };
    }

    private Ast.Expr parseBangOwnershipAlias() {
        // This is a contextual unary operator, not a macro and not a new
        // ownership semantic path. The lexer still emits IDENT / MUT / BANG.
        boolean mutableBorrow = check(MUT);
        if (mutableBorrow) advance();
        String operation = advance().lexeme();
        Token marker = consume(BANG, "expected '!' attached to ownership operation");
        if (operation.equals("copy") && check(BANG) && adjacent(marker, peek())) {
            advance();
            operation = "copy!!";
        }
        // Canonical borrow! mut and compatibility mut borrow! lower to the
        // SAME checked &mut AST. 'mutfoo' remains an ordinary identifier.
        // The qualifier is consumed only after the complete attached verb!.
        if (operation.equals("borrow") && match(MUT)) {
            if (mutableBorrow) throw error(previous(), "duplicate mutable borrow qualifier");
            mutableBorrow = true;
        }
        // Check explicitly before descending into the operand. Otherwise a
        // malformed borrow! mut mut item can fail as unrelated unary syntax
        // and masquerade as a targeted borrow-checker negative test.
        if (mutableBorrow && check(MUT)) {
            throw error(peek(), "duplicate mutable borrow qualifier");
        }
        Ast.Expr operand = parseUnary(); // postfix binds more tightly than ownership
        return mutableBorrow ? new Ast.UnaryExpr("&mut", operand)
                : new Ast.RuntimeCallExpr(operation, List.of(operand));
    }

    private boolean hasBangOwnershipAlias() {
        // Only the operation verb must be adjacent to '!'. Whitespace after
        // '!' is valid; no ordinary identifier's trailing ! becomes a macro.
        int verbIndex = current;
        if (check(MUT)) {
            verbIndex++;
            if (verbIndex >= tokens.size() || tokens.get(verbIndex).type() != IDENT
                    || !tokens.get(verbIndex).lexeme().equals("borrow")) return false;
        } else if (!check(IDENT)) {
            return false;
        }
        String verb = tokens.get(verbIndex).lexeme();
        if (!verb.equals("copy") && !verb.equals("take")
                && !verb.equals("borrow") && !verb.equals("share")) return false;
        if (verbIndex + 1 >= tokens.size()) return false;
        Token marker = tokens.get(verbIndex + 1);
        return marker.type() == BANG && adjacent(tokens.get(verbIndex), marker);
    }

    private Ast.Expr parseUnary() {
        if (unaryNesting >= MAX_UNARY_NESTING) {
            throw error(peek(), "unary expression nesting exceeds " + MAX_UNARY_NESTING);
        }
        unaryNesting++;
        try {
            return parseUnaryBody();
        } finally {
            unaryNesting--;
        }
    }

    private Ast.Expr parseUnaryBody() {
        if (hasBangOwnershipAlias()) return parseBangOwnershipAlias();
        // Signed i64's negative endpoint requires magnitude 2^63, which
        // cannot be parsed as a positive Java long. Treat exactly that
        // token sequence as a single negative literal, without broadening
        // positive literal admission or accepting malformed separators.
        if (match(MINUS)) {
            if (check(INT)) {
                String literal = peek().lexeme();
                // Leading zeros are legal in decimal runs. Compare the
                // numeric magnitude, not one canonical textual spelling.
                // Malformed separators must never be normalized into validity.
                if (!literal.endsWith("_") && !literal.contains("__")) {
                    String digits = literal.replace("_", "");
                    int first = 0;
                    while (first < digits.length() && digits.charAt(first) == '0') first++;
                    if (digits.substring(first).equals("9223372036854775808")) {
                        advance();
                        return new Ast.LiteralExpr(Long.MIN_VALUE);
                    }
                }
            }
            return new Ast.UnaryExpr("-", parseUnary());
        }
        // Former ~kind(...) collection constructors are invalid regardless of
        // whether canonical Type.of lowering exists in this backend.
        if (isTildeAggregateConstructorHead()) {
            String kind = tokens.get(current + 1).lexeme();
            String canonical = switch (kind) {
                case "fixed_array" -> "FixedArray";
                case "fixed_list" -> "FixedList";
                default -> Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
            };
            throw error(peek(), "removed collection constructor; '~' is reserved for structural typing; use "
                    + canonical + ".of(...) to construct collections");
        }
        if (match(UNICODE_NOT)) return new Ast.UnaryExpr("!", parseUnary());
        if (match(BANG, PLUS)) return new Ast.UnaryExpr(previous().lexeme(), parseUnary());
        if (match(TILDE)) {
            Token sigil = previous();
            // Typed-empty ~<T>() was a constructor alias, not structural
            // syntax; explicit typed empties use Type.of<T>().
            if (check(LT) && adjacent(sigil, peek())) {
                throw error(peek(), "'~<T>()' collection constructors are removed; "
                        + "use the canonical Type.of<T>() spelling");
            }
            // The ordinary unary ~(expr) path is independent of collection
            // construction. Bare ~(field: value) or ~(key => value) used
            // to select implicit Record/Map construction; those are also
            // removed, because tilde must not manufacture aggregates.
            if (check(LPAREN)) {
                if (checkNext(RPAREN)) {
                    throw error(peek(), "empty '~()' cannot construct an aggregate; use Type.of(...)");
                }
                int first = current + 1;
                if (first < tokens.size() && tokens.get(first).type() == ELLIPSIS) {
                    throw error(peek(), "tilde aggregate spread constructors are removed; use Type.of(...)");
                }
                if (first + 1 < tokens.size()) {
                    Token.Type keyKind = tokens.get(first).type();
                    Token.Type separator = tokens.get(first + 1).type();
                    if ((keyKind == IDENT || keyKind == STRING) && separator == COLON
                            || separator == FAT_ARROW) {
                        throw error(peek(), "tilde Map/Record construction is removed; use Map.of(...) or Record.of(...)");
                    }
                }
                if (tildeHasTopLevelMapSeparator()) {
                    throw error(peek(), "tilde Map construction is removed; use Map.of(...)");
                }
            }
            return new Ast.UnaryExpr("~", parseUnary());
        }
        if (match(AMP)) {
            if (match(MUT)) {
                throw error(previous(),
                        "Oreslang does not use '&mut' for mutable borrowing (unary pointer syntax is not supported); use 'rt mut borrow value'");
            }
            throw error(previous(),
                    "Oreslang does not use '&' for borrowing (unary pointer syntax is not supported); use 'rt borrow value'");
        }
        if (match(AWAIT)) return new Ast.AwaitExpr(parseUnary());

        // task is contextual: task(...) and identifiers named task remain legal.
        if (check(IDENT) && peek().lexeme().equals("task") && checkNext(LBRACE)) {
            advance();
            return new Ast.TaskExpr(Ast.ExecutionDomain.CPU, parseBlock(), false);
        }
        if (match(CPU, GPU)) {
            Token domainToken = previous();
            Ast.ExecutionDomain domain = domainToken.type() == CPU
                    ? Ast.ExecutionDomain.CPU : Ast.ExecutionDomain.GPU;
            if (!matchContextualKeyword("task")) {
                throw error(domainToken,
                        "synchronous " + domainToken.lexeme()
                                + " { ... } lowering is not enabled yet; use "
                                + domainToken.lexeme() + " task { ... }");
            }
            return new Ast.TaskExpr(domain, parseBlock(), false);
        }

        if (match(TRAP)) return new Ast.TrapExpr(parseUnary());
        if (match(RT)) return parseRuntimeExpression();

        if (match(NB)) {
            if (check(LBRACE)) {
                return new Ast.TaskExpr(Ast.ExecutionDomain.CPU, parseBlock(), true);
            }
            if (match(CPU, GPU)) {
                Ast.ExecutionDomain domain = previous().type() == CPU
                        ? Ast.ExecutionDomain.CPU : Ast.ExecutionDomain.GPU;
                if (!check(LBRACE)) {
                    throw error(peek(), "legacy 'nb cpu/gpu' task form requires a block");
                }
                return new Ast.TaskExpr(domain, parseBlock(), true);
            }
            if (match(CB)) {
                if (!match(WRITECH)) {
                    throw error(previous(), "'nb cb' must prefix writech");
                }
                Ast.ChannelOpExpr operation =
                        (Ast.ChannelOpExpr) parseChannelOperation(
                                Ast.ChannelOperation.WRITE,
                                Ast.WaitMode.NONBLOCKING);
                consume(PIPE, "channel callback requires '|| -> { ... }'");
                consume(PIPE, "channel callback requires '|| -> { ... }'");
                consume(ARROW, "channel callback requires '|| -> { ... }'");
                return new Ast.ChannelOpExpr(
                        operation.operation(),
                        operation.mode(),
                        operation.channel(),
                        operation.value(),
                        parseBlock());
            }
            if (match(READCH)) return parseChannelOperation(Ast.ChannelOperation.READ, Ast.WaitMode.NONBLOCKING);
            if (match(WRITECH)) return parseChannelOperation(Ast.ChannelOperation.WRITE, Ast.WaitMode.NONBLOCKING);
            if (match(SELECT)) return parseDynamicSelect(Ast.WaitMode.NONBLOCKING);
            throw error(previous(), "'nb' must prefix readch, writech, select, or cb writech");
        }

        if (check(TRY) && current + 1 < tokens.size()) {
            Token.Type next = tokens.get(current + 1).type();
            if (next == READCH || next == WRITECH || next == SELECT) {
                advance();
                if (match(READCH)) return parseChannelOperation(Ast.ChannelOperation.READ, Ast.WaitMode.IMMEDIATE);
                if (match(WRITECH)) return parseChannelOperation(Ast.ChannelOperation.WRITE, Ast.WaitMode.IMMEDIATE);
                consume(SELECT, "expected select");
                return parseDynamicSelect(Ast.WaitMode.IMMEDIATE);
            }
        }

        if (match(READCH)) return parseChannelOperation(Ast.ChannelOperation.READ, Ast.WaitMode.BLOCKING);
        if (match(WRITECH)) return parseChannelOperation(Ast.ChannelOperation.WRITE, Ast.WaitMode.BLOCKING);
        if (match(SELECT)) return parseDynamicSelect(Ast.WaitMode.BLOCKING);

        return parsePostfix();
    }

    private Ast.Expr parseRuntimeExpression() {
        boolean mutableBorrow = false;
        Token operation;
        if (match(MUT)) {
            Token mut = previous();
            operation = consume(IDENT, "expected 'borrow' after 'rt mut'");
            if (!operation.lexeme().equals("borrow")) {
                throw error(operation,
                        "'rt mut' only supports borrowing; use 'rt mut borrow value'");
            }
            mutableBorrow = true;
        } else if (match(YIELD)) {
            operation = previous(); // compatibility spelling: rt yield
        } else {
            operation = consume(IDENT, "expected runtime operation after 'rt'");
        }

        String name = operation.lexeme();
        if (mutableBorrow && !name.equals("borrow")) {
            throw error(operation, "'rt mut' may only modify borrow; use 'rt mut borrow value'");
        }
        if (operation.type() == YIELD || name.equals("yield") || name.equals("cooperate")) {
            if (match(LPAREN)) {
                if (!check(RPAREN)) {
                    throw error(peek(),
                            "rt cooperate currently takes no arguments; scheduling policy hints are not enabled yet");
                }
                consume(RPAREN, "expected ')' after rt cooperate");
            }
            return new Ast.RuntimeCallExpr("cooperate", List.of());
        }

        if (name.equals("ref") || name.equals("deref") || name.equals("unref") || name.equals("raw")) {
            throw error(operation,
                    "pointer-style rt operation '" + name
                            + "' is not valid Oreslang source; use rt copy/share/take/borrow or rt mut borrow");
        }

        if (name.equals("workflow_request")) {
            if (mutableBorrow) {
                throw error(operation, "rt mut workflow_request is not supported");
            }
            consume(LPAREN, "rt workflow_request requires one parenthesized payload");
            Ast.Expr payload = parseExpression();
            if (payload instanceof Ast.ListExpr literal && literal.elements().size() != 15) {
                throw error(operation, "rt workflow_request list literal must contain exactly 15 fields");
            }
            consume(RPAREN, "expected ')' after rt workflow_request payload");
            // The runtime validates a fixed 15-element scalar list, never an
            // arbitrary Java object, JNI pointer or host policy callback.
            return new Ast.RuntimeCallExpr("workflow_request", List.of(payload));
        }

        if (!name.equals("copy") && !name.equals("take")
                && !name.equals("borrow") && !name.equals("share") && !name.equals("fail")) {
            throw error(operation,
                    "unknown rt operation '" + name
                            + "'; supported operations are cooperate, copy, take, borrow, share, and fail");
        }

        // Both historical orders are compatibility spellings. They must
        // produce the same checked &mut AST, without weakening the borrow
        // checker or allowing a second mutable qualifier.
        if (name.equals("borrow") && match(MUT)) {
            if (mutableBorrow) throw error(previous(), "duplicate mutable borrow qualifier");
            mutableBorrow = true;
        }
        if (mutableBorrow && check(MUT)) {
            throw error(peek(), "duplicate mutable borrow qualifier");
        }
        Ast.Expr argument;
        if (match(LPAREN)) {
            if (check(RPAREN)) {
                throw error(peek(), "rt " + name + " expects exactly one argument");
            }
            argument = parseExpression();
            consume(RPAREN, "expected ')' after rt " + name + " argument");
        } else {
            // Command form has unary precedence: rt borrow value.member
            argument = parseUnary();
        }
        if (mutableBorrow) {
            // Internal AST marker only; '&mut' is never admitted as Oreslang source syntax.
            return new Ast.UnaryExpr("&mut", argument);
        }
        return new Ast.RuntimeCallExpr(name, List.of(argument));
    }

    private Ast.Expr parseChannelOperation(
            Ast.ChannelOperation operation,
            Ast.WaitMode mode) {
        if (operation == Ast.ChannelOperation.DEFAULT) {
            throw new AssertionError("default is not a standalone channel operation");
        }

        if (match(LPAREN)) {
            Ast.Expr channel = parseExpression();
            Ast.Expr value = null;
            if (operation == Ast.ChannelOperation.WRITE) {
                consume(COMMA, "writech(channel, value) requires two arguments");
                value = parseExpression();
            }
            consume(RPAREN, "expected ')' after channel operation");
            return new Ast.ChannelOpExpr(operation, mode, channel, value);
        }

        Ast.Expr channel = parseUnary();
        Ast.Expr value = null;
        if (operation == Ast.ChannelOperation.WRITE) {
            consume(COMMA, "writech command form requires 'writech channel, value'");
            value = parseExpression();
        }
        return new Ast.ChannelOpExpr(operation, mode, channel, value);
    }

    // Restricted executable source slice: bounded lexical Channel<int> and
    // Future<int> arms with pure integer projections. The synthetic select
    // bodies pass normal type/ownership checks before scheduler lowering.
    // General dynamic expressions and arbitrary arm bodies fail closed.
    private Ast.Expr parseRestrictedCountedSelect() {
        String unsupported = "counted select requires tracked Future result lowering outside restricted scalar read/Future slice";
        long count = -1;
        String dynamicCount = null;
        Token countStart = peek();
        Ast.Expr countExpression = parseExpression();
        if (!match(RPAREN)) throw error(peek(), unsupported);
        if (countExpression instanceof Ast.NameExpr name && countStart.type() == IDENT) {
            dynamicCount = name.name();
            if (dynamicCount.startsWith("$")) throw error(countStart, unsupported);
        } else {
            // The restricted count plan is pure checked lexical i64 arithmetic.
            // No calls, global lookups, assignment or suspension are admitted.
            try {
                var names = dev.oreslang.ast.CountedSelectCount.names(countExpression);
                if (names.isEmpty()) {
                    count = foldCountedConstant(countExpression, new int[]{64});
                    if (count < 0 || count > 16)
                        throw new IllegalArgumentException("count out of range");
                } else {
                    dynamicCount = dev.oreslang.ast.CountedSelectCount.SNAPSHOT;
                }
            } catch (ArithmeticException | IllegalArgumentException invalid) {
                throw error(countStart, unsupported + ": invalid or unsupported count expression");
            }
        }
        Ast.SelectStmt selected = parseStaticSelectBody(Ast.WaitMode.BLOCKING, true);
        if (selected.policy() == Ast.SelectPolicy.RANDOM || selected.arms().isEmpty()
                || selected.arms().size() > 8)
            throw error(previous(), unsupported);
        for (Ast.SelectArm arm : selected.arms()) {
            if ((arm.operation() != Ast.ChannelOperation.READ && arm.operation() != Ast.ChannelOperation.AWAIT)
                    || !(arm.channel() instanceof Ast.NameExpr source)
                    || source.name().startsWith("$") || arm.bindingName() == null
                    || arm.bindingName().startsWith("$") || arm.mutableReferent()
                    || !Ast.TypeRef.simple("int").equals(arm.bindingType())
                    || arm.body().size() != 1
                    || !(arm.body().getFirst() instanceof Ast.ReturnStmt returned)
                    || !isCountedScalarProjection(returned.value(), arm.bindingName()))
                throw error(previous(), unsupported);
        }
        if (dynamicCount != null) {
            // Runtime snapshots the lexical count once before source admission.
            // Fixed-size checked body; no count-dependent AST unrolling.
            String output = "$counted_select_output";
            var array = new Ast.TypeRef("Array", List.of(Ast.TypeRef.simple("int")), false);
            var result = new Ast.BindingStmt(Ast.BindingKind.VAL, array, output,
                    new Ast.NewExpr(array, List.of()));
            var reserve = new Ast.ExprStmt(new Ast.CallExpr(
                    new Ast.MemberExpr(new Ast.NameExpr(output), "reserve"),
                    List.of(new Ast.NameExpr(dynamicCount))));
            var stop = new Ast.IfStmt(List.of(new Ast.IfBranch(
                    new Ast.BinaryExpr(">=", new Ast.MemberExpr(new Ast.NameExpr(output), "size"),
                            new Ast.NameExpr(dynamicCount)), List.of(new Ast.BreakStmt()))), List.of());
            List<Ast.SelectArm> roundArms = new ArrayList<>();
            for (Ast.SelectArm arm : selected.arms()) {
                var returned = (Ast.ReturnStmt) arm.body().getFirst();
                var source = (Ast.NameExpr) arm.channel();
                var add = new Ast.ExprStmt(new Ast.CallExpr(
                        new Ast.MemberExpr(new Ast.NameExpr(output), "add"),
                        List.of(copyCountedScalarProjection(returned.value()))));
                roundArms.add(new Ast.SelectArm(arm.operation(), new Ast.NameExpr(source.name()),
                        null, arm.bindingKind(), arm.bindingName(), List.of(add), false, arm.bindingType()));
            }
            var round = new Ast.SelectStmt(Ast.WaitMode.BLOCKING, selected.policy(),
                    List.copyOf(roundArms), true);
            List<Ast.Stmt> body = new ArrayList<>();
            if (dynamicCount.equals(dev.oreslang.ast.CountedSelectCount.SNAPSHOT)) {
                // Type/ownership passes check this synthetic initializer.
                // Runtime evaluates it in the caller and injects the snapshot
                // rather than executing the initializer again on the task lane.
                body.add(new Ast.BindingStmt(Ast.BindingKind.VAL, Ast.TypeRef.simple("int"),
                        dynamicCount, countExpression, false));
            }
            body.addAll(List.of(result, reserve, new Ast.LoopStmt(List.of(stop, round)),
                    new Ast.ReturnStmt(new Ast.NameExpr(output))));
            return new Ast.TaskExpr(Ast.ExecutionDomain.CPU, body,
                    false, false, true, null, dynamicCount);
        }
        if (count == 0) {
            // Type-check all lexical sources but never capture/evaluate them.
            var witnesses = selected.arms().stream().map(arm ->
                    new Ast.CountedZeroWitness(((Ast.NameExpr) arm.channel()).name(), arm.operation())).toList();
            var array = new Ast.TypeRef("Array", List.of(Ast.TypeRef.simple("int")), false);
            return new Ast.TaskExpr(Ast.ExecutionDomain.CPU,
                    List.of(new Ast.ReturnStmt(new Ast.NewExpr(array, List.of()))),
                    false, false, true, null, null, witnesses);
        }
        List<Ast.Stmt> taskBody = new ArrayList<>();
        List<Ast.Expr> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String slot = "$counted_select_value_" + i;
            taskBody.add(new Ast.BindingStmt(Ast.BindingKind.LET, Ast.TypeRef.simple("int"),
                    slot, new Ast.LiteralExpr(0L)));
            List<Ast.SelectArm> roundArms = new ArrayList<>();
            for (Ast.SelectArm arm : selected.arms()) {
                var returned = (Ast.ReturnStmt) arm.body().getFirst();
                var source = (Ast.NameExpr) arm.channel();
                var projected = copyCountedScalarProjection(returned.value());
                var assignment = new Ast.ExprStmt(new Ast.AssignExpr(new Ast.NameExpr(slot), projected));
                roundArms.add(new Ast.SelectArm(arm.operation(), new Ast.NameExpr(source.name()),
                        null, arm.bindingKind(), arm.bindingName(), List.of(assignment), false, arm.bindingType()));
            }
            taskBody.add(new Ast.SelectStmt(Ast.WaitMode.BLOCKING,
                    selected.policy(), List.copyOf(roundArms), true));
            values.add(new Ast.NameExpr(slot));
        }
        taskBody.add(new Ast.ReturnStmt(new Ast.ListExpr(values, true)));
        return new Ast.TaskExpr(Ast.ExecutionDomain.CPU, taskBody, false, false, true);
    }

    private static long foldCountedConstant(Ast.Expr expression, int[] budget) {
        if (--budget[0] < 0) throw new IllegalArgumentException("count expression budget");
        if (expression instanceof Ast.LiteralExpr literal && literal.value() instanceof Long value)
            return value;
        if (expression instanceof Ast.BinaryExpr binary) {
            long left = foldCountedConstant(binary.left(), budget);
            long right = foldCountedConstant(binary.right(), budget);
            return switch (binary.operator()) {
                case "+" -> Math.addExact(left, right);
                case "-" -> Math.subtractExact(left, right);
                case "*" -> Math.multiplyExact(left, right);
                default -> throw new IllegalArgumentException("unsupported count operator");
            };
        }
        throw new IllegalArgumentException("nonconstant count expression");
    }

    private static boolean isCountedScalarProjection(Ast.Expr expression, String binding) {
        // Check the projection with a bounded iterative walk before cloning.
        // Unrolling a large source tree must not amplify compiler stack or AST use.
        if (expression == null) return false;
        java.util.ArrayDeque<Ast.Expr> pending = new java.util.ArrayDeque<>();
        pending.push(expression);
        int budget = 64;
        while (!pending.isEmpty()) {
            if (--budget < 0) return false;
            Ast.Expr item = pending.pop();
            if (item instanceof Ast.LiteralExpr literal && literal.value() instanceof Long) continue;
            if (item instanceof Ast.NameExpr name && name.name().equals(binding)) continue;
            if (!(item instanceof Ast.BinaryExpr binary)
                    || !List.of("+", "-", "*").contains(binary.operator())) return false;
            pending.push(binary.right());
            pending.push(binary.left());
        }
        return true;
    }

    private static Ast.Expr copyCountedScalarProjection(Ast.Expr expression) {
        if (expression instanceof Ast.LiteralExpr literal) return new Ast.LiteralExpr(literal.value());
        if (expression instanceof Ast.NameExpr name) return new Ast.NameExpr(name.name());
        Ast.BinaryExpr binary = (Ast.BinaryExpr) expression;
        return new Ast.BinaryExpr(binary.operator(),
                copyCountedScalarProjection(binary.left()), copyCountedScalarProjection(binary.right()));
    }

    private Ast.Expr parseDynamicSelect(Ast.WaitMode mode) {
        // Static select expressions will return a tracked Future, including
        // explicit select<T>. Do not lower them as legacy blocking/detached
        // statements or pretend that parsing a generic admits the feature.
        if (match(LPAREN)) {
            if (mode == Ast.WaitMode.IMMEDIATE)
                throw error(previous(), "counted select requires tracked Future result lowering");
            // The same narrow typed binding + pure int projection establishes
            // the inferred result independently of contextual annotation.
            return parseRestrictedCountedSelect();
        }
        if (match(LT)) {
            // First executable value-select slice: exactly one default arm,
            // no event operands to evaluate or commit. The checked CPU task
            // machinery supplies deferred execution, scope and cleanup.
            // Keep all event and counted forms fail-closed.
            if (!peek().lexeme().equals("int"))
                throw error(peek(), "typed static select<T> requires tracked Future result lowering");
            advance();
            if (!match(GT) || mode == Ast.WaitMode.IMMEDIATE)
                throw error(peek(), "typed/count select requires tracked Future result lowering");
            if (match(LPAREN)) return parseRestrictedCountedSelect();
            Ast.SelectStmt selected = parseStaticSelectBody(Ast.WaitMode.NONBLOCKING, false);
            if (selected.arms().size() != 1
                    || selected.arms().getFirst().operation() != Ast.ChannelOperation.DEFAULT)
                throw error(previous(), "event select requires tracked Future result lowering");
            return new Ast.TaskExpr(Ast.ExecutionDomain.CPU,
                    selected.arms().getFirst().body(), false, true);
        }
        Ast.SelectPolicy policy = parseSelectPolicy();
        if (check(LBRACE) || check(OVER)
                || (check(IDENT) && peek().lexeme().equals("over"))) {
            throw error(peek(), "static select expression requires tracked Future result lowering");
        }
        consume(FROM, "dynamic select requires 'select from cases'");
        Ast.Expr cases = parseUnary();
        return new Ast.DynamicSelectExpr(mode, policy, cases);
    }

    /**
     * Compiler-owned factories must lower to the same aggregate AST as the
     * corresponding literals, not to dynamic members or host collections.
     * Only kinds with an existing checked value representation are admitted.
     */
    private String builtinOfFactory(Ast.Expr callee) {
        if (!(callee instanceof Ast.MemberExpr member)
                || !(member.receiver() instanceof Ast.NameExpr owner)) return null;
        String name = owner.name();
        if (member.member().equals("of_entries") && name.equals("Map")
                && !shadowedOfTypes.contains(name)) return "Map.of_entries";
        if (!member.member().equals("of")) return null;
        if (!BUILTIN_OF_TYPES.contains(name) || shadowedOfTypes.contains(name))
            return null;
        return name;
    }

    private boolean unsupportedBuiltInOf(Ast.Expr callee) {
        if (!(callee instanceof Ast.MemberExpr member)
                || !member.member().equals("of")
                || !(member.receiver() instanceof Ast.NameExpr owner)) return false;
        return PENDING_OF_TYPES.contains(owner.name())
                && !shadowedOfTypes.contains(owner.name());
    }

    private Ast.Expr parseFactoryOf(String kind, List<Ast.TypeRef> explicitTypes) {
        if (kind.equals("Map") || kind.equals("Map.of_entries")) {
            boolean entryOnly = kind.equals("Map.of_entries");
            Ast.TypeRef witness = null;
            if (explicitTypes != null) {
                if (explicitTypes.size() != 2
                        || explicitTypes.stream().anyMatch(Ast.TypeRef::isNamedParameter)) {
                    throw error(previous(), "Map.of<K,V> requires exactly two positional type witnesses");
                }
                witness = new Ast.TypeRef("Map", explicitTypes, false);
            }
            if (match(RPAREN)) {
                return witness == null
                        ? new Ast.ContextualEmptyMapExpr()
                        : new Ast.TypedEmptyAggregateExpr(witness);
            }
            return parseMapOfEntries(witness, entryOnly);
        }
        if (explicitTypes != null) {
            throw error(previous(), kind + ".of<T>(...) requires independently checked typed factory lowering");
        }
        if (kind.equals("Record")) {
            throw error(previous(), "Record.of must be parsed through the checked static-field record parser");
        }
        // This is exactly the existing array/tuple literal argument grammar:
        // spreads, named call labels and foreign argument dispatch are not
        // silently enabled by a new factory spelling.
        List<Ast.Expr> elements = parseArgumentsUntil(RPAREN);
        consume(RPAREN, "expected ')' after " + kind + ".of arguments");
        if (kind.equals("Array") && elements.isEmpty()) {
            throw error(previous(), "Array.of() needs a checked element type; an empty untyped array cannot infer T");
        }
        return kind.equals("Tuple") ? new Ast.TupleExpr(elements) : new Ast.ListExpr(elements, true);
    }

    private Ast.Expr parsePostfix() {
        return parsePostfixSuffix(parsePrimary());
    }

    /** Preserve postfix member/index behavior for typed aggregate literals. */
    private Ast.Expr parsePostfixSuffix(Ast.Expr expr) {
        while (true) {
            // A pending standard-library factory is not a dynamic host
            // member. User-defined/shadowed nominal methods remain ordinary
            // calls so the typechecker, not this parser, resolves identity.
            if (unsupportedBuiltInOf(expr)) {
                throw error(peek(), "collection factory requires compiler-owned "
                        + "type, ownership and guest lowering; unsupported: "
                        + ((Ast.NameExpr) ((Ast.MemberExpr) expr).receiver()).name()
                        + ".of");
            }
            String factory = builtinOfFactory(expr);
            if (factory != null && check(LT) && adjacent(previous(), peek())
                    && looksLikeTypeArgumentCall()) {
                List<Ast.TypeRef> types = parseCallTypeArguments();
                consume(LPAREN, "expected '(' after " + (factory.equals("Map.of_entries") ? factory : factory + ".of") + " type arguments");
                if (factory.equals("Record")) {
                    throw error(previous(), "Record.of does not admit explicit generic arguments");
                }
                expr = parseFactoryOf(factory, types);
            } else if (factory != null && check(LPAREN)) {
                if (factory.equals("Record")) {
                    expr = parseRecordOfLiteral();
                } else {
                    advance(); // '('; factory owns closing ')'
                    expr = parseFactoryOf(factory, null);
                }
            } else if (factory != null) {
                // Do not allow a bare compiler-owned factory to escape as
                // a first-class dynamic/host member until it has a typed
                // function-value representation with ownership and AOT proof.
                throw error(peek(), (factory.equals("Map.of_entries") ? factory : factory + ".of")
                        + " must be invoked as a checked collection factory");
            } else if (check(LT) && adjacent(previous(), peek()) && looksLikeTypeArgumentCall()) {
                List<Ast.TypeRef> typeArguments = parseCallTypeArguments();
                consume(LPAREN, "expected '(' after call type arguments");
                rejectUnsupportedActorFactory(expr);
                List<Ast.Expr> args = parseCallArgumentsUntil(RPAREN);
                consume(RPAREN, "expected ')' after arguments");
                expr = new Ast.CallExpr(expr, typeArguments, args);
            } else if (match(LPAREN)) {
                rejectUnsupportedActorFactory(expr);
                List<Ast.Expr> args = parseCallArgumentsUntil(RPAREN);
                consume(RPAREN, "expected ')' after arguments");
                expr = new Ast.CallExpr(expr, args);
            } else if (match(DOT, DOUBLE_COLON)) {
                Token.Type qualifier = previous().type();
                String member = consumeMemberName();
                if (qualifier == DOUBLE_COLON && expr instanceof Ast.NameExpr namespace) {
                    if (namespace.name().equals("pkg")) {
                        expr = new Ast.MemberExpr(new Ast.NameExpr(PKG_ROOT_NAMESPACE), member);
                    } else if (namespace.name().equals("imports")) {
                        expr = new Ast.MemberExpr(new Ast.NameExpr(IMPORTS_NAMESPACE), member);
                    } else {
                        expr = new Ast.MemberExpr(expr, member);
                    }
                } else {
                    expr = new Ast.MemberExpr(expr, member);
                }
            } else if (expr instanceof Ast.NameExpr name
                    && check(LBRACE) && isLocalTypeName(name.name())) {
                expr = parseLocalAggregateConstructor(name.name());
            } else if (match(LBRACKET)) {
                Ast.Expr index = parseExpression();
                consume(RBRACKET, "expected ']' after index");
                expr = new Ast.IndexExpr(expr, index);
            } else break;
        }
        return expr;
    }

    /**
     * These reserved Actor namespace operations are compiler intrinsics, not
     * dynamically dispatched user methods. Until typed actor graph IR and
     * transactional graph admission exist, reject the call at the parser gate.
     * Otherwise an unknown method or host interop fallback could bypass
     * staged actor ownership, CONTROL exclusion or generation fencing.
     */
    private void rejectUnsupportedActorFactory(Ast.Expr callee) {
        if (!(callee instanceof Ast.MemberExpr member)
                || !(member.receiver() instanceof Ast.NameExpr owner)
                || !owner.name().equals("Actor")) return;
        if (!member.member().equals("pipe") && !member.member().equals("connect")) return;
        throw error(previous(), "Actor." + member.member()
                + "(...) requires compiler-owned actor factory graph IR, "
                + "typed port and ActorRef admission, and atomic supervised publication; "
                + "not yet enabled on the Java backend");
    }

    private List<Ast.TypeRef> parseCallTypeArguments() {
        consume(LT, "expected '<' before call type arguments");
        List<Ast.TypeRef> arguments = new ArrayList<>();
        boolean seenNamed = false;
        if (!check(GT)) {
            do {
                if (check(IDENT) && checkNext(EQUAL)) {
                    String name = advance().lexeme();
                    consume(EQUAL, "expected '=' after named generic argument");
                    arguments.add(Ast.TypeRef.namedParameter(name, parseTypeRef()));
                    seenNamed = true;
                } else {
                    if (seenNamed) {
                        throw error(peek(), "positional generic arguments must precede named arguments");
                    }
                    arguments.add(parseTypeRef());
                }
            } while (match(COMMA));
        }
        consume(GT, "expected '>' after call type arguments");
        return List.copyOf(arguments);
    }

    private boolean looksLikeTypeArgumentCall() {
        return looksLikeTypeArgumentCallAt(current);
    }

    private boolean looksLikeTypeArgumentCallAt(int startIndex) {
        int depth = 0;
        for (int i = startIndex; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LT) depth++;
            else if (type == GT) {
                depth--;
                if (depth == 0) return i + 1 < tokens.size() && tokens.get(i + 1).type() == LPAREN;
                if (depth < 0) return false;
            } else if (type == SEMICOLON || type == QUESTION || type == COLON || type == EOF
                    || (type == EQUAL && (depth == 0 || i == 0
                            || tokens.get(i - 1).type() != IDENT))) {
                // '=' is allowed only after a generic parameter name inside <...>.
                return false;
            }
        }
        return false;
    }

    private String consumeCallableName(String message) {
        Token token = peek();
        if (token.type() == IDENT
                || isReservedCallableName(token.type())
                || isContextualStatementCallableName(token.type())
                || token.type() == TRUE || token.type() == FALSE) {
            advance();
            return token.lexeme();
        }
        throw error(token, message);
    }

    private boolean reservedCallableNameFollowedByInvocation(int nameIndex) {
        int nextIndex = nameIndex + 1;
        if (nextIndex >= tokens.size()) return false;
        Token next = tokens.get(nextIndex);
        if (next.type() == LPAREN) return true;
        return next.type() == LT
                && adjacent(tokens.get(nameIndex), next)
                && looksLikeTypeArgumentCallAt(nextIndex);
    }

    private static boolean isReservedCallableName(Token.Type type) {
        return type == STOP || type == DO || type == DONE;
    }

    private static boolean isContextualStatementCallableName(Token.Type type) {
        return type == LOOP || type == BLOCK || type == SELECT
                || type == READCH || type == WRITECH || type == NB;
    }

    private String consumeStaticObjectKeyName(String message) {
        if (match(STRING)) return previous().lexeme();
        Token token = peek();
        if (isMemberNameToken(token.type())) {
            advance();
            return token.lexeme();
        }
        throw error(token, message);
    }

    private String consumeMemberName() {
        Token token = peek();
        if (isMemberNameToken(token.type())) {
            if (isReservedCallableName(token.type())
                    && token.type() != DONE
                    && !reservedCallableNameFollowedByInvocation(current)) {
                throw error(token, "'" + token.lexeme()
                        + "' is reserved and may only be used as a function name in a call or as an object/map key");
            }
            advance();
            return token.lexeme();
        }
        throw error(token, "expected member name after '.'");
    }

    /**
     * Reserved words remain illegal lexical identifiers, but member names live
     * in a separate namespace. This permits APIs such as SharedMutex.new(...)
     * without allowing declarations such as `val new = ...`.
     */
    private static boolean isMemberNameToken(Token.Type type) {
        return switch (type) {
            case IDENT,
                    DEFINE, CLASS, MODULE, NAMESPACE, IMPORT, FROM, AS, EXTENDS, IMPLEMENTS,
                    TRY, CATCH, FINALLY, END, FI, IF, DO, ELSE, THEN,
                    NEW, SPAWN, STOP, DONE, AWAIT, ASYNC, NLEX, TRAP, NB, SELECT, READCH, WRITECH, ACTOR, ISOACTOR, SHARED, DEF, FNC, ROUTINE, FOR, OF, LOOP, BLOCK, BREAK, CONTINUE, YIELD, SUPER, ELSEIF, SWITCH, MATCH, MATCHES, EQ, NEQ, IS, WHEN, CASE, DEFAULT, FIRST, CB, UNTRUSTED, TYPE, TYPES, TYPEOF,
                    INTERFACE, TRAIT, STRUCT, IMPL, ABSTRACT, VOID, STATIC, PUB, PRIVATE, STRUCTURAL, RETURN, DEFER,
                    VAL, CONST, LET, MUT, SELF, TRUE, FALSE, NULL, OBJ, ARR -> true;
            default -> false;
        };
    }

    // A guest-language literal must not leak Java's NumberFormatException.
    // Both expression and pattern literals use the same signed-i64 bound.
    // Wider integers require a separately admitted type and backend contract.
    private long parseSignedI64(Token token) {
        String literal = token.lexeme();
        // Do not silently turn malformed token separators into valid integers.
        if (literal.endsWith("_") || literal.contains("__")) {
            throw error(token, "malformed integer separator: " + literal);
        }
        try {
            return Long.parseLong(literal.replace("_", ""));
        } catch (NumberFormatException invalid) {
            throw error(token, "integer literal overflows signed 64-bit: " + literal);
        }
    }

    private Ast.Expr parsePrimary() {
        // Closed single-break value expression, not a general loop CFG.
        // Preserve contextual loop(...) callable references and ordinary
        // statement loops; only expression-position loop followed by {
        // select this narrow scalar profile.
        if (check(LOOP) && checkNext(LBRACE)) {
            Token site = advance();
            consume(LBRACE, "expected '{' after value loop");
            consume(BREAK, "value loop requires exactly one valued break");
            if (check(SEMICOLON) || check(RBRACE)) {
                throw error(peek(), "E_BREAK_VALUE: value loop requires a break operand");
            }
            Ast.Expr value = parseExpression();
            if (!(value instanceof Ast.LiteralExpr || value instanceof Ast.UnaryExpr
                    || value instanceof Ast.BinaryExpr || value instanceof Ast.ConditionalExpr)) {
                throw error(site, "value-loop operand requires typed CFG lowering");
            }
            consume(SEMICOLON, "expected ';' after value break");
            consume(RBRACE, "expected '}' after single value break");
            if (check(LPAREN)) throw error(peek(), "calling a computed value loop");
            return value;
        }
        if (match(MATCH)) return parseValueMatch();
        if (canonicalAttoSource && match(IF, IFNOT, UNLESS)) return parseValueIf(previous().type());
        if (match(INT)) return new Ast.LiteralExpr(parseSignedI64(previous()));
        if (match(FLOAT)) return new Ast.LiteralExpr(Double.parseDouble(previous().lexeme().replace("_", "")));
        if (match(IMAG)) {
            String raw = previous().lexeme().substring(0, previous().lexeme().length() - 1).replace("_", "");
            return new Ast.LiteralExpr(new Ast.Imaginary(Double.parseDouble(raw)));
        }
        if (match(STRING)) return new Ast.LiteralExpr(previous().lexeme());
        if (match(TEMPLATE_LITERAL)) return TemplateLiterals.lower(previous().lexeme());
        if (match(SYMBOL_LITERAL)) return new Ast.LiteralExpr(dev.oreslang.runtime.OresSymbol.process(previous().lexeme()));
        if (match(TRUE)) return new Ast.LiteralExpr(Boolean.TRUE);
        if (match(FALSE)) return new Ast.LiteralExpr(Boolean.FALSE);
        if (match(NULL)) throw error(previous(), "standalone null values are forbidden; use Option<T>");
        if (match(SELF)) return new Ast.NameExpr("self");
        // 'actor' remains reserved, but in expression position it names the
        // actor-local runtime namespace (actor.gc and future local primitives).
        if (match(ACTOR)) return new Ast.NameExpr("actor");
        // loop/block are contextual statement keywords: parseStatement only
        // consumes them when followed by '{'. In every expression position they
        // remain valid references to same-named callables, including first-class
        // function values (not only direct calls).
        if (isContextualStatementCallableName(peek().type())) {
            return new Ast.NameExpr(advance().lexeme());
        }
        if (isReservedCallableName(peek().type())
                && reservedCallableNameFollowedByInvocation(current)) {
            return new Ast.NameExpr(advance().lexeme());
        }
        if (isContextualIdentifier("infer")
                && (checkNext(STRUCT)
                    || (checkNext(IDENT) && current + 1 < tokens.size()
                        && tokens.get(current + 1).lexeme().equals("record")))) {
            advance(); // contextual infer
            if (match(STRUCT)) {
                return parseObjectLiteral(Ast.ObjectKind.INFERRED_STRUCT, List.of());
            }
            consumeContextualIdentifier("record", "expected 'record' after infer");
            return parseObjectLiteral(Ast.ObjectKind.INFERRED_RECORD, List.of());
        }
        if (isContextualIdentifier("record") && checkNext(LBRACE)) {
            advance(); // contextual record
            return parseObjectLiteral(Ast.ObjectKind.RECORD, List.of());
        }
        if (isContextualIdentifier("tuple") && checkNext(LPAREN)) {
            advance();
            consume(LPAREN, "expected '(' after tuple");
            List<Ast.Expr> items = parseArgumentsUntil(RPAREN);
            consume(RPAREN, "expected ')' after explicit tuple");
            return new Ast.TupleExpr(items);
        }
        if (match(IDENT, SHARED, UNTRUSTED)) {
            return new Ast.NameExpr(previous().lexeme());
        }
        if (match(SPAWN)) {
            Ast.TypeRef type = parseTypeRef();
            consume(LPAREN, "expected '(' after actor type");
            List<Ast.Expr> args = parseArgumentsUntil(RPAREN);
            consume(RPAREN, "expected ')' after actor spawn arguments");
            // Grouped spawn options bind to spawn, not immutable with-update.
            // The empty profile has no requests or expressions to evaluate.
            // Preserve ordinary spawn defaults and all ordinary type/runtime checks.
            // Never discard a nonempty group: those require typed lowering.
            if (isContextualIdentifier("with") && checkNext(LBRACE)) {
                Token site = peek();
                var options = parseGroupedSpawnOptions();
                if (options.groups().stream().anyMatch(group -> !group.entries().isEmpty())) {
                    throw error(site, "E_SPAWN_OPTIONS_UNSUPPORTED: grouped actor spawn requires typed initialization, resource admission and runtime lowering");
                }
            }
            // Keep actor creation semantically distinct from ordinary class construction.
            // RuntimeCallExpr is an internal intrinsic encoding; source syntax is only
            // `spawn ActorType(...)`, never `rt spawn` or `new ActorType(...)`.
            return new Ast.RuntimeCallExpr("spawn", List.of(new Ast.NewExpr(type, args)));
        }
        if (match(NEW)) {
            Ast.TypeRef type = parseTypeRef();
            consume(LPAREN, "expected '(' after new type");
            List<Ast.Expr> args = parseArgumentsUntil(RPAREN);
            consume(RPAREN, "expected ')' after constructor arguments");
            if (type.name().equals("Proxy")) {
                // Compile-time-only, exact single-construction v1. In particular,
                // no runtime reflection, arbitrary expression inference, actor
                // reference transport or unqualified fallback is permitted.
                if (!type.arguments().isEmpty() || type.inferArguments()
                        || (args.size() != 1 && args.size() != 2)
                        || !(args.getFirst() instanceof Ast.NewExpr wrapped)
                        || !wrapped.type().arguments().isEmpty()
                        || wrapped.type().inferArguments()
                        || !wrapped.type().name().matches("[A-Za-z_][A-Za-z_0-9]*")) {
                    throw error(previous(), "AOT_PROXY_UNSUPPORTED: expected "
                            + "new Proxy(new LocalClass(...)) or "
                            + "new Proxy(new LocalClass(...), new LocalHooks(...)); "
                            + "runtime target inference is forbidden");
                }
                if (args.size() == 1) {
                    staticProxyTargets.add(wrapped.type().name());
                    return new Ast.NewExpr(
                            Ast.TypeRef.simple(wrapped.type().name() + "Proxy"),
                            List.of(wrapped));
                }
                if (!(args.get(1) instanceof Ast.NewExpr interceptor)
                        || !interceptor.type().arguments().isEmpty()
                        || interceptor.type().inferArguments()
                        || !interceptor.type().name().matches("[A-Za-z_][A-Za-z_0-9]*")) {
                    throw error(previous(), "AOT_PROXY_UNSUPPORTED: interceptors require "
                            + "a statically known new LocalHooks(...) constructor");
                }
                StaticProxySynthesis.InterceptorRequest request =
                        new StaticProxySynthesis.InterceptorRequest(
                                wrapped.type().name(), interceptor.type().name());
                if (!staticProxyInterceptors.contains(request)) {
                    staticProxyInterceptors.add(request);
                }
                return new Ast.NewExpr(
                        Ast.TypeRef.simple(request.generatedName()),
                        List.of(wrapped, interceptor));
            }
            return new Ast.NewExpr(type, args);
        }
        if (match(OBJ)) {
            throw error(previous(),
                    "obj{...} has been removed; use infer struct{...} for a closed inferred struct "
                            + "or struct{field: Type,...}{...} for an explicit closed struct");
        }
        if (match(STRUCT)) {
            if (matchContextualKeyword("infer") || match(INFER)) {
                throw error(previous(), "inferred structs use 'infer struct{...}'");
            }
            if (check(LBRACE)) {
                // Preserve the explicit two-brace shape/initializer spelling.
                // In canonical source a single brace group constructs a closed
                // inferred struct, never a Record and never a statement block.
                if (canonicalAttoSource && !hasFollowingStructInitializer()) {
                    return parseObjectLiteral(Ast.ObjectKind.INFERRED_STRUCT, List.of());
                }
                return parseExplicitStructLiteral();
            }
            throw error(peek(), "struct expression requires 'infer struct{...}' or 'struct{field: Type,...}{...}'");
        }
        if (match(ARR)) {
            consume(LBRACKET, "expected '[' after arr");
            List<Ast.Expr> items = parseArgumentsUntil(RBRACKET);
            consume(RBRACKET, "expected ']' after arr literal");
            // arr[...] is an explicit array constructor, unlike bare [...].
            // Do not permit contextual coercion to a finite Tuple type.
            return new Ast.ListExpr(items, true);
        }
        if (match(NLEX)) {
            if (check(PIPE)) return parsePipeLambda(true);
            if (check(FNC) && checkNext(LPAREN)) {
                advance(); // anonymous fnc expression shares the lambda AST
                return parseLambda(true);
            }
            if (check(LPAREN) && looksLikeLambda()) return parseLambda(true);
            throw error(previous(), "'nlex' in expression position must prefix a lambda");
        }
        if (check(FNC) && checkNext(LPAREN)) {
            advance(); // explicit anonymous fnc expression, not a declaration
            return parseLambda(false);
        }
        if (check(PIPE)) return parsePipeLambda(false);
        if (check(LPAREN) && looksLikeLambda()) return parseLambda(false);
        if (match(LPAREN)) {
            Ast.Expr first = parseExpression();
            if (match(COMMA)) {
                List<Ast.Expr> items = new ArrayList<>();
                items.add(first);
                do items.add(parseExpression()); while (match(COMMA));
                consume(RPAREN, "expected ')' after tuple");
                return new Ast.TupleExpr(items);
            }
            consume(RPAREN, "expected ')' after expression");
            return first;
        }
        if (match(LBRACKET)) {
            List<Ast.Expr> items = parseArgumentsUntil(RBRACKET);
            consume(RBRACKET, "expected ']'");
            return new Ast.ListExpr(items);
        }
        throw error(peek(), "expected expression");
    }

    /**
     * This guard never accepts map literals. It prevents a computed key on the
     * left of '=>' from being misdiagnosed as a unary/grouped expression.
     * Track nested delimiters so an inner expression's tokens cannot turn a
     * plain ~(expression) into a map literal.
     */
    private boolean tildeHasTopLevelMapSeparator() {
        if (!check(LPAREN)) return false;
        int parens = 0;
        int brackets = 0;
        int braces = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) parens++;
            else if (type == RPAREN) {
                parens--;
                if (parens == 0) return false;
            } else if (type == LBRACKET) brackets++;
            else if (type == RBRACKET) brackets--;
            else if (type == LBRACE) braces++;
            else if (type == RBRACE) braces--;
            else if (type == FAT_ARROW && parens == 1 && brackets == 0 && braces == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * First runnable slice of the tilde aggregate contract. Reuse the
     * compiler's existing closed readonly record AST; do not create a
     * Java Map, dynamic object shape or unchecked runtime conversion.
     * Map literals have a separate typed guest runtime promotion gate.
     */
    /**
     * All entries are owned by one Map literal. The parser never inserts into
     * storage; type/ownership preflight and atomic commit occur before a guest
     * Map value becomes visible. Mixed record ':' syntax is not accepted.
     * The '=>' token is consumed outside parseExpression's pipeline grammar.
     */
    // Only the unqualified, non-shadowed inline factory spelling is admitted
    // here. Standalone Pair/Entry values and arbitrary constructor aliases
    // require the resolved-symbol typed aggregate implementation.
    private boolean inlineProductFactory(String name) {
        return !shadowedOfTypes.contains(name) && current + 3 < tokens.size()
                && check(IDENT) && peek().lexeme().equals(name)
                && tokens.get(current + 1).type() == DOT
                && tokens.get(current + 2).lexeme().equals("of")
                && tokens.get(current + 3).type() == LPAREN;
    }

    private String readInlinePairString() {
        Token key = consume(STRING, "Pair.of requires a string literal key in this bounded factory profile");
        return key.lexeme();
    }

    private String readInlineRecordPairName() {
        String name = readInlinePairString();
        // Use the same lexer as parameter declarations, and require exact
        // spelling, not merely one token after trivia/comment removal.
        List<Token> parts = new Lexer(name).scan();
        if (parts.size() != 2 || parts.getFirst().type() != IDENT
                || !parts.getFirst().lexeme().equals(name)) {
            throw error(previous(), "Record.of Pair key must be a valid static parameter identifier");
        }
        return name;
    }

    private void openInlineProductFactory() {
        advance(); advance(); advance();
        consume(LPAREN, "expected '(' after inline product factory");
    }

    private Ast.MapLiteralExpr parseMapOfEntries(Ast.TypeRef mapType, boolean entryOnly) {
        List<Ast.MapLiteralEntry> entries = new ArrayList<>();
        java.util.Map<String, Integer> constantStringKeyKinds = new java.util.HashMap<>();
        do {
            if (check(ELLIPSIS)) {
                throw error(peek(), "map spreads require explicit source-ownership "
                        + "and transactional batch proofs");
            }
            Ast.Expr key;
            Ast.Expr value;
            int itemKind = 0; // legacy arrow entry
            if (inlineProductFactory("Pair") || inlineProductFactory("Entry")) {
                boolean pair = inlineProductFactory("Pair");
                if (entryOnly && pair)
                    throw error(peek(), "Map.of_entries accepts Entry items only, not Pair");
                itemKind = pair ? 1 : 2;
                openInlineProductFactory();
                // Dynamic Pair keys are admitted only with an explicit,
                // concrete string Map witness. The existing Map type gate
                // independently checks the key expression against that witness.
                // Contextual/union witnesses still use the literal-only path
                // until nominal Pair provenance survives into typed HIR.
                boolean explicitStringKey = mapType != null
                        && mapType.arguments().size() == 2
                        && java.util.Set.of("string", "String").contains(mapType.arguments().getFirst().name())
                        && mapType.arguments().getFirst().arguments().isEmpty()
                        && !mapType.arguments().getFirst().inferArguments();
                if (pair && !explicitStringKey) {
                    if (!check(STRING)) throw error(peek(), "Pair.of requires a string key");
                    key = parsePrimary();
                    if (!(key instanceof Ast.LiteralExpr))
                        throw error(previous(), "Pair.of requires a pure string literal key");
                } else {
                    key = parseExpression();
                    if (pair && key instanceof Ast.LiteralExpr literal
                            && !(literal.value() instanceof String))
                        throw error(previous(), "Pair.of requires a string key");
                }
                consume(COMMA, "product factory requires key and value");
                value = parseExpression();
                consume(RPAREN, "product factory requires exactly two arguments");
            } else {
                key = parseExpression();
                consume(FAT_ARROW, "map literals require '=>' between key and value");
                value = parseExpression();
            }
            // Reject mixed-kind constant duplicates in the new factory syntax.
            // Preserve legacy homogeneous arrow duplicate detection at runtime.
            if (key instanceof Ast.LiteralExpr literal && literal.value() instanceof String stringKey) {
                Integer previousKind = constantStringKeyKinds.putIfAbsent(stringKey, itemKind);
                if (previousKind != null && (previousKind != 0 || itemKind != 0)) {
                    throw error(previous(), "duplicate constant key in Map.of");
                }
            }
            entries.add(new Ast.MapLiteralEntry(key, value));
            if (entries.size() > 4096) {
                throw error(peek(), "map literal exceeds 4096 entries");
            }
            if (!match(COMMA)) break;
        } while (!check(RPAREN));
        consume(RPAREN, "expected ')' to close map literal");
        return new Ast.MapLiteralExpr(mapType, entries);
    }

    private Ast.ObjectExpr parseRecordOfLiteral() {
        consume(LPAREN, "expected '(' after Record.of");
        List<Ast.ObjectField> fields = new ArrayList<>();
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        Boolean pairStyle = null;
        while (!check(RPAREN)) {
            if (match(ELLIPSIS)) {
                // The existing type/ownership checker rejects spreads until
                // their lifetime and partial-move proof is implemented.
                fields.add(Ast.ObjectField.spreading(parseExpression()));
            } else {
                boolean pair = inlineProductFactory("Pair");
                if (pairStyle != null && pairStyle != pair)
                    throw error(peek(), "Record.of cannot mix named fields and Pair items in this profile");
                pairStyle = pair;
                String name;
                if (pair) {
                    openInlineProductFactory();
                    name = readInlineRecordPairName();
                    consume(COMMA, "Pair.of requires exactly two arguments");
                } else {
                    Token fieldToken = peek();
                    name = consumeStaticObjectKeyName("expected static Record.of field name");
                    if (check(FAT_ARROW)) {
                        throw error(peek(), "mixed record and map entries are forbidden: Record.of does not accept Entry arrows");
                    }
                    // Retain existing colon spelling for compatibility.
                    if (match(EQUAL)) {
                        if (fieldToken.type() != IDENT)
                            throw error(fieldToken, "Record.of equals field must be a parameter identifier");
                    } else consume(COLON, "Record.of requires '=' or legacy ':' after a field name");
                }
                if (!names.add(name)) {
                    throw error(previous(), "duplicate named field in Record.of: '" + name + "'");
                }
                Ast.Expr value = parseExpression();
                if (pair) consume(RPAREN, "Pair.of requires exactly two arguments");
                fields.add(Ast.ObjectField.named(name, value));
            }
            if (!match(COMMA)) break;
            if (check(RPAREN)) break; // an optional trailing comma
        }
        consume(RPAREN, "expected ')' after Record.of literal");
        return new Ast.ObjectExpr(fields, Ast.ObjectKind.RECORD, List.of());
    }

    // Token-only classification, no speculative parsing or fallback on error.
    // The first balanced brace group is a type shape only when a second group
    // follows. Nested expressions and strings cannot counterfeit delimiters.
    private boolean hasFollowingStructInitializer() {
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type kind = tokens.get(i).type();
            if (kind == EOF) break;
            if (kind == LBRACE) depth++;
            else if (kind == RBRACE && --depth == 0) {
                return i + 1 < tokens.size() && tokens.get(i + 1).type() == LBRACE;
            }
        }
        throw error(peek(), "expected '}' after struct");
    }

    private Ast.ObjectExpr parseExplicitStructLiteral() {
        consume(LBRACE, "expected '{' after struct");
        List<Ast.StructFieldSpec> declared = new ArrayList<>();
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        if (!check(RBRACE)) {
            do {
                String name = consumeStaticObjectKeyName("expected explicit struct field name");
                if (!names.add(name)) {
                    throw error(previous(), "duplicate explicit struct field '" + name + "'");
                }
                consume(COLON, "expected ':' after explicit struct field name");
                declared.add(new Ast.StructFieldSpec(name, parseTypeRef()));
            } while (match(COMMA));
        }
        consume(RBRACE, "expected '}' after explicit struct shape");
        if (!check(LBRACE)) {
            throw error(peek(), "explicit struct shape must be followed by an initializer: struct{field: Type}{field: value}");
        }
        return parseObjectLiteral(Ast.ObjectKind.EXPLICIT_STRUCT, declared);
    }

    private Ast.ObjectExpr parseObjectLiteral(
            Ast.ObjectKind kind,
            List<Ast.StructFieldSpec> declaredFields) {
        boolean plainRecord = kind == Ast.ObjectKind.RECORD;
        boolean inferredStruct = kind == Ast.ObjectKind.INFERRED_STRUCT;
        boolean inferredRecord = kind == Ast.ObjectKind.INFERRED_RECORD;
        boolean explicitStruct = kind == Ast.ObjectKind.EXPLICIT_STRUCT;
        consume(LBRACE, plainRecord
                ? "expected '{' after record"
                : inferredStruct ? "expected '{' after infer struct"
                : inferredRecord ? "expected '{' after infer record"
                : "expected '{' after explicit struct shape");
        List<Ast.ObjectField> fields = new ArrayList<>();
        if (!check(RBRACE)) {
            do {
                if (check(BACKTICK) || check(TEMPLATE_LITERAL)) {
                    throw error(peek(),
                            "struct fields must be statically named for AOT compilation; "
                                    + "computed/backtick field names are not supported");
                }
                if (match(ELLIPSIS)) {
                    // Preserve spread as a checked aggregate item, never a magic
                    // field name or a call-argument tuple expansion.
                    fields.add(Ast.ObjectField.spreading(parseExpression()));
                } else {
                    String name = consumeStaticObjectKeyName("expected struct field name");
                    consume(COLON, "expected ':' after struct field name");
                    fields.add(Ast.ObjectField.named(name, parseExpression()));
                }
            } while (match(COMMA));
        }
        consume(RBRACE, explicitStruct
                ? "expected '}' after explicit struct initializer"
                : inferredRecord ? "expected '}' after infer record literal"
                : "expected '}' after infer struct literal");
        return new Ast.ObjectExpr(fields, kind, declaredFields);
    }

    private Ast.LambdaExpr parseLambda(boolean nonLexical) {
        consume(LPAREN, "expected '('");
        List<Ast.Param> params = parseParametersUntil(RPAREN);
        consume(RPAREN, "expected ')' after lambda parameters");
        consume(ARROW, "expected '->' after lambda parameters");
        return parseLambdaBody(params, nonLexical);
    }

    private Ast.LambdaExpr parsePipeLambda(boolean nonLexical) {
        return parsePipeLambda(nonLexical, false);
    }

    private Ast.LambdaExpr parsePipeLambda(boolean nonLexical, boolean inlinePipelineStage) {
        consume(PIPE, "expected '|'");
        List<Ast.Param> params = new ArrayList<>();
        if (!check(PIPE)) {
            do {
                // Bare 'mut s' changes the local parameter binding, whereas
                // 'borrow mut s' is a fixed, exclusive mutable-borrow view.
                // Both shorthand forms need a contextual callable signature to
                // infer the referent type; typed forms remain unambiguous.
                if (match(MUT)) {
                    String name = consume(IDENT, "expected mutable lambda parameter name").lexeme();
                    if (!check(COMMA) && !check(PIPE)) {
                        throw error(peek(), "mutable shorthand must be '|mut name|' or '|mut name, ...|'");
                    }
                    params.add(new Ast.Param(Ast.TypeRef.inferred(), name, false, true));
                } else if (check(IDENT) && peek().lexeme().equals("borrow")
                        && checkNext(MUT)
                        && current + 3 < tokens.size()
                        && (tokens.get(current + 3).type() == COMMA
                            || (tokens.get(current + 3).type() == PIPE
                                && current + 4 < tokens.size()
                                && tokens.get(current + 4).type() == ARROW))) {
                    advance(); // contextual 'borrow'
                    advance(); // 'mut' belongs to the referent, not the binding
                    String name = consume(IDENT, "expected borrowed lambda parameter name").lexeme();
                    params.add(new Ast.Param(
                            Ast.TypeRef.borrowed(Ast.TypeRef.inferred(), true), name, false, false));
                } else if (check(IDENT) && (checkNext(COMMA) || checkNext(PIPE))) {
                    String name = advance().lexeme();
                    params.add(new Ast.Param(Ast.TypeRef.inferred(), name, false, false));
                } else if (check(IDENT) && checkNext(COLON)) {
                    String name = advance().lexeme();
                    consume(COLON, "expected ':' after lambda parameter name");
                    Ast.TypeRef type = parsePipeParameterType();
                    boolean mutable = match(MUT);
                    params.add(new Ast.Param(type, name, false, mutable));
                } else {
                    Ast.TypeRef type = parsePipeParameterType();
                    boolean mutable = match(MUT);
                    String name = consume(IDENT, "expected lambda parameter name").lexeme();
                    params.add(new Ast.Param(type, name, false, mutable));
                }
            } while (match(COMMA));
        }
        consume(PIPE, "expected closing '|' after lambda parameters");
        consume(ARROW, "lambdas use the slim arrow '->'");
        return parseLambdaBody(params, nonLexical, inlinePipelineStage);
    }

    private Ast.LambdaExpr parseLambdaBody(List<Ast.Param> params, boolean nonLexical) {
        return parseLambdaBody(params, nonLexical, false);
    }

    private Ast.LambdaExpr parseLambdaBody(
            List<Ast.Param> params, boolean nonLexical, boolean inlinePipelineStage) {
        // Immediate '{' denotes an inferred block. Preserve expression-bodied
        // lambdas, while recognizing an explicitly typed *braced* block:
        //   || -> void { cleanup(); }
        //   |x: int| -> int { return x + 1; }
        if (check(LBRACE)) {
            return new Ast.LambdaExpr(params, null, parseBlock(), nonLexical);
        }
        // Speculate only on the return type plus a following block opener.
        // A failed type probe cannot consume an expression-bodied lambda.
        int start = current;
        Ast.TypeRef resultType = null;
        try {
            resultType = parseTypeRef();
        } catch (IllegalArgumentException ignored) {
            // Not a type header: parse the existing expression-body grammar.
        }
        if (resultType != null && check(LBRACE)) {
            return new Ast.LambdaExpr(params, null, parseBlock(), nonLexical, resultType);
        }
        // Scalar type names are contextual IDENTs, so '|| -> int;' would
        // otherwise be accepted as an expression-bodied lambda returning an
        // identifier named 'int'. Refuse an unambiguous bare scalar type
        // followed by an expression terminator: typed lambdas need '{...}'.
        // Do not reject '|| -> int + 1' or any other legitimate expression.
        if (resultType != null
                && resultType.arguments().isEmpty()
                && !resultType.inferArguments()
                && isBareScalarReturnType(resultType.name())
                && (check(SEMICOLON) || check(COMMA) || check(RPAREN)
                    || check(RBRACE) || check(EOF))) {
            throw error(peek(), "explicit lambda result type requires a braced body");
        }
        current = start;
        // The lambda's body is an ordinary value expression. However when it
        // appears unparenthesized immediately after |>, a following top-level
        // |> belongs to the enclosing pipeline. Group (x |> inner) explicitly
        // to request a nested pipeline inside the lambda.
        return new Ast.LambdaExpr(
                params, inlinePipelineStage ? parseConditional() : parseExpression(),
                null, nonLexical);
    }

    private static boolean isBareScalarReturnType(String name) {
        return switch (name) {
            case "int", "uint", "i8", "i16", "i32", "i64",
                    "u8", "u16", "u32", "u64",
                    "bool", "boolean", "string", "float",
                    "f32", "f64", "decimal", "bigint" -> true;
            default -> false;
        };
    }

    private boolean looksLikeLambda() {
        if (matchGuardStartToken >= 0 && current >= matchGuardStartToken) {
            // At the guard's own lexical depth a following '->' belongs to
            // the match arm. Preserve real nested lambdas in calls/groups:
            // their parentheses are at positive depth from the guard start.
            int depth = 0;
            for (int i = matchGuardStartToken; i < current; i++) {
                Token.Type type = tokens.get(i).type();
                if (type == LPAREN || type == LBRACKET || type == LBRACE) depth++;
                else if (type == RPAREN || type == RBRACKET || type == RBRACE) depth--;
            }
            if (depth == 0) return false;
        }
        // An arm's slim arrow may follow a parenthesized expression, e.g.
        // 'when await (nb readch gate) -> { ... }'. A closing ')' followed
        // by '->' is not sufficient evidence of a lambda declaration:
        // lambda parameters require declaration syntax, never these
        // expression-only prefix operators.
        if (current + 1 < tokens.size()) {
            Token.Type first = tokens.get(current + 1).type();
            if (first == NB || first == AWAIT || first == READCH
                    || first == WRITECH || first == RT || first == TRAP
                    || first == TRY || first == SELECT || first == BANG || first == UNICODE_NOT) {
                return false;
            }
        }
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) depth++;
            else if (type == RPAREN) {
                depth--;
                if (depth == 0) return i + 1 < tokens.size() && tokens.get(i + 1).type() == ARROW;
            }
        }
        return false;
    }

    private List<Ast.Expr> parseCallArgumentsUntil(Token.Type terminator) {
        if (check(terminator)) return List.of();
        List<Ast.Expr> args = new ArrayList<>();
        java.util.Set<String> seenLabels = new java.util.LinkedHashSet<>();
        boolean namedStarted = false;
        do {
            if (check(terminator)) throw error(peek(), "empty call argument");
            if (match(ELLIPSIS)) {
                if (namedStarted) {
                    throw error(previous(),
                            "positional spread arguments cannot follow named arguments");
                }
                args.add(new Ast.SpreadExpr(parseExpression()));
                continue;
            }
            if (check(IDENT) && checkNext(COLON)) {
                namedStarted = true;
                String name = advance().lexeme();
                consume(COLON, "expected ':' after named argument");
                if (!seenLabels.add(name)) {
                    throw error(previous(), "duplicate named argument '" + name + "'");
                }
                args.add(new Ast.NamedArgExpr(name, parseExpression()));
                continue;
            }
            if (namedStarted) {
                throw error(peek(), "positional arguments cannot follow named arguments");
            }
            args.add(parseExpression());
        } while (match(COMMA));
        return List.copyOf(args);
    }

    // Closed syntax-only shape: cannot enter executable Ast.Expr visitors.
    private GroupedSpawnOptions<Ast.Expr> parseGroupedSpawnOptions() {
        advance();
        consume(LBRACE, "expected '{' after spawn with");
        List<GroupedSpawnOptions.Group<Ast.Expr>> sections = new ArrayList<>();
        var seenSections = java.util.EnumSet.noneOf(GroupedSpawnOptions.Section.class);
        while (!check(RBRACE) && !check(EOF)) {
            Token section = consume(IDENT, "expected spawn option section");
            var kind = switch (section.lexeme()) {
                case "schedule" -> GroupedSpawnOptions.Section.SCHEDULE;
                case "resources" -> GroupedSpawnOptions.Section.RESOURCES;
                case "init" -> GroupedSpawnOptions.Section.INIT;
                default -> throw error(section, "E_SPAWN_OPTION_SECTION: unknown section " + section.lexeme());
            };
            if (!seenSections.add(kind))
                throw error(section, "E_SPAWN_OPTION_DUPLICATE: duplicate section " + section.lexeme());
            consume(LBRACE, "expected '{' after spawn option section");
            List<GroupedSpawnOptions.Entry<Ast.Expr>> entries = new ArrayList<>();
            java.util.Set<String> seenKeys = new java.util.HashSet<>();
            while (!check(RBRACE) && !check(EOF)) {
                if (entries.size() >= 256)
                    throw error(peek(), "E_SPAWN_OPTION_LIMIT: at most 256 entries per section in this parser profile");
                Token key = consume(IDENT, "expected spawn option key");
                String field = key.lexeme();
                if (!seenKeys.add(field))
                    throw error(key, "E_SPAWN_OPTION_DUPLICATE: duplicate key " + field);
                GroupedSpawnOptions.ScheduleKey scheduleKey = null;
                GroupedSpawnOptions.ResourceKey resourceKey = null;
                switch (kind) {
                    case SCHEDULE -> scheduleKey = switch (field) {
                        case "max_parallelism" -> GroupedSpawnOptions.ScheduleKey.MAX_PARALLELISM;
                        case "affinity" -> GroupedSpawnOptions.ScheduleKey.AFFINITY;
                        case "pool" -> GroupedSpawnOptions.ScheduleKey.POOL;
                        default -> throw error(key, "E_SPAWN_OPTION_KEY: unknown schedule key " + field);
                    };
                    case RESOURCES -> resourceKey = switch (field) {
                        case "heap_size" -> GroupedSpawnOptions.ResourceKey.HEAP_SIZE;
                        case "stack_size" -> GroupedSpawnOptions.ResourceKey.STACK_SIZE;
                        case "mailbox_capacity" -> GroupedSpawnOptions.ResourceKey.MAILBOX_CAPACITY;
                        default -> throw error(key, "E_SPAWN_OPTION_KEY: unknown resources key " + field);
                    };
                    case INIT -> { }
                }
                consume(EQUAL, "expected '=' after spawn option key");
                Ast.Expr value = parseExpression();
                entries.add(switch (kind) {
                    case SCHEDULE -> new GroupedSpawnOptions.ScheduleEntry<>(scheduleKey, value);
                    case RESOURCES -> new GroupedSpawnOptions.ResourceEntry<>(resourceKey, value);
                    case INIT -> new GroupedSpawnOptions.InitEntry<>(field, value);
                });
                consume(SEMICOLON, "expected ';' after spawn option value");
            }
            consume(RBRACE, "expected '}' after spawn option entries");
            sections.add(new GroupedSpawnOptions.Group<>(kind, entries));
        }
        consume(RBRACE, "expected '}' after grouped spawn options");
        return new GroupedSpawnOptions<>(sections);
    }

    private List<Ast.Expr> parseArgumentsUntil(Token.Type terminator) {
        if (check(terminator)) return List.of();
        List<Ast.Expr> args = new ArrayList<>();
        do args.add(parseExpression()); while (match(COMMA));
        return args;
    }

    private void consumeMemberTerminator(Token.Type structuralTerminator, String message) {
        if (match(SEMICOLON) || check(structuralTerminator)) return;
        throw error(peek(), message);
    }

    private void consumeClassFieldTerminator(String message) {
        if (match(SEMICOLON)) return;
        // An implicit separator is a newline, never adjacent tokens on the
        // same source line. In particular a modifier such as 'pub' must not
        // bypass the same-line guard that already applies to bare 'mtd'.
        // Preserve the historical terminal 'end' spelling for the class.
        if (!check(END) && current > 0 && previous().line() >= peek().line()) {
            throw error(peek(), message + " (use ';' or a new line between class members)");
        }
        if (check(AT) || check(END) || check(PUB) || check(PRIVATE)
                || check(STATIC) || check(ABSTRACT) || check(ASYNC) || check(SUSPENDS) || check(LBRACKET)
                || isBindingKind(peek().type())
                || (check(IDENT) && (checkNext(COLON) || checkNext(LPAREN)))
                || isNextContextualMemberLine()) return;
        throw error(peek(), message);
    }

    // A field without a semicolon can be followed by the new contextual
    // declaration spellings, but only on a *new* source line. Never treat
    // arbitrary same-line tokens as an implicit field terminator.
    private boolean isNextContextualMemberLine() {
        if (current == 0 || previous().line() >= peek().line()) return false;
        if (check(FNC)) return true;
        if (check(MUT)) return looksLikeTypeFirstClassField();
        if (!check(IDENT)) return false;
        String word = peek().lexeme();
        // Keep this boundary consistent with matchExplicitMethodMarker:
        // keyword-named methods (mtd select(), mtd do()) and symbol methods
        // may follow a semicolon-free field on a separate source line.
        Token.Type following = current + 1 < tokens.size()
                ? tokens.get(current + 1).type() : EOF;
        boolean markedMethod = (word.equals("method") || word.equals("mtd"))
                && (following == IDENT || following == LBRACKET
                    || isReservedCallableName(following)
                    || isContextualStatementCallableName(following)
                    || following == TRUE || following == FALSE);
        return markedMethod
                || ((word.equals("suspend") || word.equals("method") || word.equals("mtd")
                        || word.equals("final") || word.equals("critical"))
                    && (checkNext(IDENT) || checkNext(MUT) || checkNext(FNC) || checkNext(LPAREN)))
                || looksLikeTypeFirstClassField();
    }

    private boolean hasAnnotation(List<Ast.Annotation> annotations, String name) {
        for (Ast.Annotation annotation : annotations) if (annotation.name().equals(name)) return true;
        return false;
    }

    private record CallableStructural(boolean enabled, List<Ast.Annotation> annotations) { }

    private boolean isCallableStructuralAnnotation(Ast.Annotation annotation) {
        return annotation.name().equals("structural") || annotation.name().equals("Structural");
    }

    private CallableStructural normalizeCallableStructural(
            List<Ast.Annotation> annotations,
            boolean modifierStructural) {
        boolean annotationStructural = false;
        List<Ast.Annotation> remaining = new ArrayList<>(annotations.size());
        for (Ast.Annotation annotation : annotations) {
            if (!isCallableStructuralAnnotation(annotation)) {
                remaining.add(annotation);
                continue;
            }
            if (!annotation.arguments().isEmpty()) {
                throw error(previous(), "@structural does not take arguments");
            }
            if (annotationStructural) {
                throw error(previous(), "duplicate @structural callable annotation");
            }
            annotationStructural = true;
        }
        if (modifierStructural && annotationStructural) {
            throw error(previous(), "duplicate structural callable modifier; use either 'structural' or '@structural', not both");
        }
        return new CallableStructural(modifierStructural || annotationStructural, List.copyOf(remaining));
    }

    /** Only recognized, zero-argument copy policies can annotate a class. */
    private void validateCopyPolicyClassAnnotations(List<Ast.Annotation> annotations) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Ast.Annotation annotation : annotations) {
            String name = annotation.name();
            if (!List.of("NoCopy", "WarnOnCopy", "Copyable").contains(name)) {
                throw error(previous(), "@" + name + " on class declarations would be silently discarded; unsupported annotation");
            }
            if (!annotation.arguments().isEmpty() || !seen.add(name)) {
                throw error(previous(), "E_COPY_ANNOTATION: invalid or duplicate @" + name + " on class declaration");
            }
        }
        if (seen.contains("NoCopy") && (seen.contains("Copyable") || seen.contains("WarnOnCopy"))) {
            throw error(previous(), "E_COPY_ANNOTATION: conflicting copy policies on class");
        }
    }

    /**
     * Fail closed until these declaration nodes preserve typed annotation metadata.
     * Silently dropping metadata can bypass compile-time macro, ABI and safety contracts.
     * Module, function, method and field annotations have AST storage and are unaffected.
     */
    private void rejectDiscardedDeclarationAnnotations(List<Ast.Annotation> annotations, String target) {
        if (!annotations.isEmpty()) {
            throw error(previous(), "@" + annotations.getFirst().name() + " on " + target
                    + " would be silently discarded; declaration annotation storage is not implemented");
        }
    }

    /** A return-type annotation must never survive on non-callable AST slots. */
    private void rejectNonCallableReturnAnnotation(List<Ast.Annotation> annotations, String target) {
        for (Ast.Annotation annotation : annotations) {
            if (annotation.name().equals("Ret")) {
                throw error(previous(), "@Ret is only valid on callable return signatures, not " + target);
            }
        }
    }

    private void rejectCallableStructuralAnnotation(List<Ast.Annotation> annotations, String target) {
        for (Ast.Annotation annotation : annotations) {
            if (isCallableStructuralAnnotation(annotation)) {
                throw error(previous(), "@structural is compile-time callable metadata and is not valid on " + target);
            }
        }
    }

    private void consumeExpressionStatementTerminator(Ast.Expr expression, String message) {
        if (containsExpressionBodyLambda(expression)) {
            consume(SEMICOLON, "expression-bodied lambda statements require an explicit ';'");
            return;
        }
        consumeStatementTerminator(message);
    }

    private void consumeExpressionClassFieldTerminator(Ast.Expr expression, String message) {
        if (containsExpressionBodyLambda(expression)) {
            consume(SEMICOLON, "expression-bodied lambda statements require an explicit ';'");
            return;
        }
        consumeClassFieldTerminator(message);
    }

    private boolean containsExpressionBodyLambda(Ast.Expr expression) {
        if (expression == null) return false;
        if (expression instanceof Ast.LambdaExpr lambda) {
            return lambda.expressionBody() != null;
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            return containsExpressionBodyLambda(binary.left()) || containsExpressionBodyLambda(binary.right());
        }
        if (expression instanceof Ast.TrapExpr trapped) {
            // A call-site trap must never hide an expression-bodied lambda
            // from the required explicit statement/class-field terminator.
            return containsExpressionBodyLambda(trapped.expression());
        }
        if (expression instanceof Ast.UnaryExpr unary) {
            return containsExpressionBodyLambda(unary.operand());
        }
        if (expression instanceof Ast.AssignExpr assign) {
            return containsExpressionBodyLambda(assign.target()) || containsExpressionBodyLambda(assign.value());
        }
        if (expression instanceof Ast.ConditionalExpr conditional) {
            return containsExpressionBodyLambda(conditional.condition())
                    || containsExpressionBodyLambda(conditional.whenTrue())
                    || containsExpressionBodyLambda(conditional.whenFalse());
        }
        if (expression instanceof Ast.TypeTestExpr typeTest) {
            return containsExpressionBodyLambda(typeTest.value());
        }
        if (expression instanceof Ast.PatternTestExpr patternTest) {
            return containsExpressionBodyLambda(patternTest.value());
        }
        if (expression instanceof Ast.CaptureExpr capture) {
            return containsExpressionBodyLambda(capture.value());
        }
        if (expression instanceof Ast.CastExpr cast) {
            return containsExpressionBodyLambda(cast.value());
        }
        if (expression instanceof Ast.SpreadExpr spread) {
            return containsExpressionBodyLambda(spread.expression());
        }
        if (expression instanceof Ast.CallExpr call) {
            if (containsExpressionBodyLambda(call.callee())) return true;
            return call.arguments().stream().anyMatch(this::containsExpressionBodyLambda);
        }
        if (expression instanceof Ast.RuntimeCallExpr runtime) {
            return runtime.arguments().stream().anyMatch(this::containsExpressionBodyLambda);
        }
        if (expression instanceof Ast.MemberExpr member) {
            return containsExpressionBodyLambda(member.receiver());
        }
        if (expression instanceof Ast.IndexExpr index) {
            return containsExpressionBodyLambda(index.receiver()) || containsExpressionBodyLambda(index.index());
        }
        if (expression instanceof Ast.NewExpr created) {
            return created.arguments().stream().anyMatch(this::containsExpressionBodyLambda);
        }
        if (expression instanceof Ast.AwaitExpr awaited) {
            return containsExpressionBodyLambda(awaited.expression());
        }
        if (expression instanceof Ast.ChannelOpExpr channel) {
            return containsExpressionBodyLambda(channel.channel())
                    || containsExpressionBodyLambda(channel.value());
        }
        if (expression instanceof Ast.DynamicSelectExpr select) {
            return containsExpressionBodyLambda(select.cases());
        }
        if (expression instanceof Ast.ListExpr list) {
            return list.elements().stream().anyMatch(this::containsExpressionBodyLambda);
        }
        if (expression instanceof Ast.TupleExpr tuple) {
            return tuple.elements().stream().anyMatch(this::containsExpressionBodyLambda);
        }
        if (expression instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                if (containsExpressionBodyLambda(field.value())) return true;
            }
        }
        return false;
    }

    private void consumeStatementTerminator(String message) {
        if (match(SEMICOLON) || isSafeStatementBoundary() || isImplicitNewlineTerminator()) return;
        throw error(peek(), message);
    }

    private boolean isImplicitNewlineTerminator() {
        if (current == 0 || check(EOF)) return false;
        return previous().line() < peek().line();
    }

    private boolean isSafeStatementBoundary() {
        return check(RBRACE) || check(FI) || check(END) || check(ELSE) || check(ELSEIF)
                || check(CATCH) || check(FINALLY) || isBareDoneDelimiter() || check(EOF);
    }

    private Ast.BindingKind parseBindingKind() {
        if (match(CONST)) return Ast.BindingKind.CONST;
        if (match(VAL)) return Ast.BindingKind.VAL;
        if (match(LET)) return Ast.BindingKind.LET;
        throw error(peek(), "expected const, val, or let");
    }

    private boolean parseReferentMutability(Ast.BindingKind kind) {
        if (kind == Ast.BindingKind.VAL) {
            if (match(MUT)) {
                throw error(previous(), "'val' already means 'const mut'; 'val mut' is a duplicate mutability qualifier");
            }
            return true;
        }
        return match(MUT);
    }

    private Ast.BindingKind parseForOfBindingKind() {
        return parseBindingKind();
    }

    private void requireForOfBindingKind(Ast.BindingKind kind) {
        if (kind == null) throw error(peek(), "for-of binding kind is required");
    }

    private IllegalArgumentException forOfUndeclaredBindingError() {
        String subject = check(IDENT) ? " '" + peek().lexeme() + "'" : "";
        return error(peek(),
                "variable used before declared" + subject
                        + "; for-of bindings require an explicit const/val/let declaration");
    }

    private boolean isBindingKind(Token.Type type) { return type == CONST || type == VAL || type == LET; }

    private boolean matchContextualStructuralModifier() {
        if (!check(IDENT) || !peek().lexeme().equals("structural")) return false;
        // Preserve ordinary identifiers such as:
        //   structural(): int { ... }
        //   structural<T>(T value): T { ... }
        //   structural: int
        // The modifier spelling is only consumed when it is not already
        // syntactically the member/declaration name.
        if (checkNext(LPAREN) || checkNext(LT) || checkNext(COLON)) return false;
        advance();
        return true;
    }

    // 'suspend' is an alias of 'suspends' only in modifier position; preserve
    // instance methods actually named suspend(...).
    private boolean matchContextualSuspendModifier() {
        if (!isContextualIdentifier("suspend")) return false;
        if (!checkNext(IDENT) && !checkNext(FNC)) return false;
        advance();
        return true;
    }

    // Both 'method' and 'mtd' are contextual class/actor markers, not global
    // reserved tokens (the separate named-alias extension syntax also uses
    // 'mtd'). Keep a member literally named method(...) or mtd(...) legal.
    private boolean matchExplicitMethodMarker() {
        if (!isContextualIdentifier("method") && !isContextualIdentifier("mtd")) return false;
        // Permit the same callable names after a marker as bare methods, e.g.
        // mtd select(), mtd do(), mtd mtd(), and [Symbol.iterator]().
        // Otherwise adding an optional marker would change admissible names.
        Token.Type nextType = current + 1 < tokens.size()
                ? tokens.get(current + 1).type() : EOF;
        if (nextType != IDENT && nextType != LBRACKET && nextType != STATIC
                && !isReservedCallableName(nextType)
                && !isContextualStatementCallableName(nextType)
                && nextType != TRUE && nextType != FALSE) return false;
        advance();
        return true;
    }

    private boolean isAdjacentCompoundKeyword(String first, String second) {
        if (current + 2 >= tokens.size()) return false;
        Token left = tokens.get(current);
        Token dash = tokens.get(current + 1);
        Token right = tokens.get(current + 2);
        return left.type() == IDENT && left.lexeme().equals(first)
                && dash.type() == MINUS && right.type() == IDENT
                && right.lexeme().equals(second)
                && adjacent(left, dash) && adjacent(dash, right);
    }

    private boolean matchContextualShared() {
        if (match(SHARED)) return true;
        if (check(IDENT) && peek().lexeme().equals("shared")) {
            advance();
            return true;
        }
        return false;
    }

    /**
     * Matches words that are keywords only in a specific grammar position.
     *
     * <p>{@code case} and {@code when} intentionally lex as IDENT so they remain
     * legal variable/parameter names. Grammar productions opt into their keyword
     * meaning explicitly at switch/select arms and match guards.</p>
     */
    private boolean matchContextualKeyword(String... keywords) {
        if (!check(IDENT)) return false;
        String lexeme = peek().lexeme();
        for (String keyword : keywords) {
            if (lexeme.equals(keyword)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private static boolean isIdentifierLikeToken(Token.Type type) {
        return type == IDENT || type == SHARED || type == UNTRUSTED;
    }

    private boolean match(Token.Type... types) {
        for (Token.Type type : types) {
            if (check(type)) { advance(); return true; }
        }
        return false;
    }

    private boolean matchAdjacentBangEqKeyword() {
        if (current + 1 >= tokens.size()) return false;
        Token bang = tokens.get(current);
        Token eq = tokens.get(current + 1);
        if (bang.type() != BANG || eq.type() != EQ || !adjacent(bang, eq)) return false;
        advance();
        advance();
        return true;
    }

    private Token consume(Token.Type type, String message) {
        if (check(type)) return advance();
        throw error(peek(), message);
    }

    private boolean check(Token.Type type) {
        return type == IDENT
                ? isIdentifierLikeToken(peek().type())
                : peek().type() == type;
    }
    private boolean checkNext(Token.Type type) { return current + 1 < tokens.size() && tokens.get(current + 1).type() == type; }

    private boolean adjacent(Token left, Token right) {
        return left.line() == right.line() && right.column() == left.column() + left.lexeme().length();
    }

    private boolean checkAdjacentPair(Token.Type type) {
        return current + 1 < tokens.size()
                && tokens.get(current).type() == type
                && tokens.get(current + 1).type() == type
                && adjacent(tokens.get(current), tokens.get(current + 1));
    }

    private boolean matchAdjacentPair(Token.Type type) {
        if (!checkAdjacentPair(type)) return false;
        advance();
        advance();
        return true;
    }

    private boolean matchAdjacentTriple(Token.Type type) {
        if (current + 2 >= tokens.size()) return false;
        Token first = tokens.get(current);
        Token second = tokens.get(current + 1);
        Token third = tokens.get(current + 2);
        if (first.type() != type || second.type() != type || third.type() != type
                || !adjacent(first, second) || !adjacent(second, third)) return false;
        advance();
        advance();
        advance();
        return true;
    }

    private boolean matchSingleOperator(Token.Type type) {
        if (!check(type) || checkAdjacentPair(type)) return false;
        advance();
        return true;
    }
    private boolean isLegacyFnSpelling() {
        return check(IDENT) && peek().lexeme().equals("fn");
    }

    private boolean checkNextLexeme(String lexeme) {
        return current + 1 < tokens.size() && tokens.get(current + 1).type() == IDENT
                && tokens.get(current + 1).lexeme().equals(lexeme);
    }
    private Token advance() { if (!check(EOF)) current++; return previous(); }
    private Token peek() { return tokens.get(current); }
    private Token previous() { return tokens.get(current - 1); }

    private IllegalArgumentException error(Token token, String message) {
        return new IllegalArgumentException("Oreslang parse error at " + token.line() + ":" + token.column() + ": " + message);
    }

    private record Modifiers(Ast.Visibility visibility, boolean visibilitySeen, boolean async, boolean suspends, boolean generator, boolean generatorStarred, boolean structural, boolean nonLexical, boolean trapped, boolean isStatic, boolean isAbstract, boolean shared, boolean untrusted) { }
}

