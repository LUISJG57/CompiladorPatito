import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

public class Main {
    public static void main(String[] args) throws Exception {

        // Lee el archivo fuente pasado como argumento, o usa stdin
        CharStream input = args.length > 0
            ? CharStreams.fromFileName(args[0])
            : CharStreams.fromStream(System.in);

        // ── LEXER ──────────────────────────────────────────
        PatitoLexer  lexer  = new PatitoLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        // Imprime todos los tokens reconocidos
        tokens.fill();
        System.out.println("=== TOKENS ===");
        for (Token t : tokens.getTokens()) {
            if (t.getType() != Token.EOF) {
                System.out.printf("%-20s -> '%s'%n",
                    PatitoLexer.VOCABULARY.getSymbolicName(t.getType()),
                    t.getText());
            }
        }

        // ── PARSER ─────────────────────────────────────────
        PatitoParser parser = new PatitoParser(tokens);

        // Listener de errores personalizado (muestra errores claros)
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?,?> recognizer,
                                    Object offendingSymbol,
                                    int line, int charPos,
                                    String msg, RecognitionException e) {
                System.err.printf("ERROR SINTÁCTICO línea %d:%d → %s%n",
                    line, charPos, msg);
            }
        });

        ParseTree tree = parser.programa(); // regla inicial

        System.out.println("\n=== ÁRBOL SINTÁCTICO ===");
        System.out.println(tree.toStringTree(parser));

        // ── ANÁLISIS SEMÁNTICO ─────────────────────────────────────────────
        PatitoSemanticListener semantic = new PatitoSemanticListener();
        ParseTreeWalker.DEFAULT.walk(semantic, tree);

        System.out.println("\nDIRECTORIO DE FUNCIONES");
        System.out.println(semantic.getFuncDirectory());

        System.out.println("\nTABLA DE VARIABLES GLOBALES");
        System.out.println(semantic.getGlobalVarTable());

        System.out.println("\nTABLA DE CONSTANTES (dirección -> literal)");
        System.out.print(semantic.getConstants());

        if (semantic.hasErrors()) {
            System.out.println("\nERRORES SEMÁNTICOS");
            semantic.getErrors().forEach(System.out::println);
        } else {
            System.out.println("Análisis semántico OK.");
        }

        // ── LEYENDA dirección -> nombre (para leer los cuádruplos) ─────────
        System.out.println("\n=== LEYENDA DIRECCIONES ===");
        System.out.println("Globales:");
        printVarAddresses(semantic.getGlobalVarTable());
        for (FuncInfo f : semantic.getFuncDirectory().getAll().values()) {
            System.out.println("Locales de " + f.name + ":");
            printVarAddresses(f.localVars);
        }
        System.out.println("Constantes:");
        semantic.getConstants().getLabels()
            .forEach((addr, lit) -> System.out.printf("    %-6d -> %s%n", addr, lit));

        // ── CUÁDRUPLOS ─────────────────────────────────────────────
        System.out.println("\n=== CUÁDRUPLOS (con direcciones virtuales) ===");
        System.out.print(semantic.getQuadruples());
    }

    // Imprime las variables de una tabla como  dirección -> nombre : tipo
    private static void printVarAddresses(VarTable table) {
        table.getAll().values().forEach(v ->
            System.out.printf("    %-6d -> %s : %s%n", v.address, v.name, v.type));
    }
}