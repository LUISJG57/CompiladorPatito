import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

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

        // Listener de errores personalizado (muestra errores claros y cuenta cuántos hubo)
        final int[] syntaxErrors = {0};
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?,?> recognizer,
                                    Object offendingSymbol,
                                    int line, int charPos,
                                    String msg, RecognitionException e) {
                syntaxErrors[0]++;
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

        // ── OBJ + EJECUCIÓN EN LA MÁQUINA VIRTUAL ──────────────────────────
        boolean canRun = !semantic.hasErrors() && syntaxErrors[0] == 0;
        if (canRun) {
            String objPath = (args.length > 0 ? args[0] : "program") + ".pobj";
            writeObjFile(objPath, semantic.getQuadruples(), semantic.getConstants());
            System.out.println("\n(Archivo objeto generado: " + objPath + ")");

            System.out.println("\n=== EJECUCIÓN (Máquina Virtual) ===");
            VirtualMachine.load(objPath).run();
        } else {
            System.out.println("\n(No se ejecuta la Máquina Virtual: el programa tiene errores)");
        }
    }

    // Imprime las variables de una tabla como  dirección -> nombre : tipo
    private static void printVarAddresses(VarTable table) {
        table.getAll().values().forEach(v ->
            System.out.printf("    %-6d -> %s : %s%n", v.address, v.name, v.type));
    }

    // Escribe el archivo objeto (.pobj): constantes + cuádruplos.
    // Es lo único que la Máquina Virtual necesita para ejecutar.
    private static void writeObjFile(String path, QuadrupleQueue quads, ConstantTable constants)
            throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            pw.println("%%CONSTANTS");
            for (Map.Entry<Integer, String> e : constants.getLabels().entrySet()) {
                int addr = e.getKey();
                String lit = e.getValue();
                String type, val;
                if (addr >= VirtualMemory.CONST_STR_BASE)        { type = "STR";   val = VirtualMachine.escape(lit); }
                else if (addr >= VirtualMemory.CONST_FLOAT_BASE)  { type = "FLOAT"; val = lit; }
                else                                              { type = "INT";   val = lit; }
                pw.println(addr + " " + type + " " + val);
            }
            pw.println("%%QUADRUPLES");
            for (Quadruple q : quads.getAll()) {
                pw.println(q.id + " " + q.op + " " + q.leftOp + " " + q.rightOp + " " + q.result);
            }
            pw.println("%%END");
        }
    }
}