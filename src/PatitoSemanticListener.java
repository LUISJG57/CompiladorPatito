import java.util.ArrayList;
import java.util.List;

public class PatitoSemanticListener extends PatitoBaseListener {

    private final FuncDirectory funcDirectory = new FuncDirectory();
    private final VarTable globalVarTable = new VarTable();
    private final List<String> errors = new ArrayList<>();

    // null = alcance global (vars del programa)
    // non-null = dentro de una declaración de función
    private String currentFunc = null;

    // FUNCIONES

    // Punto neurálgico 1: entrada a declaración de función -> registrar en directorio, cambiar alcance a local
    @Override
    public void enterFuncs(PatitoParser.FuncsContext ctx) {
        String funcName = ctx.ID().getText();
        SemanticCube.Type retType = parseReturnType(ctx.tipoOpcional());

        if (!funcDirectory.addFunc(funcName, retType)) {
            errors.add("SEMÁNTICO [línea " + ctx.ID().getSymbol().getLine()
                + "]: Función doblemente declarada '" + funcName + "'");
        }
        currentFunc = funcName;
    }

    // Punto neurálgico 2: salida de declaración de función -> regresar al alcance global
    @Override
    public void exitFuncs(PatitoParser.FuncsContext ctx) {
        currentFunc = null;
    }

    // PARÁMETROS

    // Punto neurálgico 3: primer parámetro
    @Override
    public void enterParams(PatitoParser.ParamsContext ctx) {
        if (currentFunc == null || !funcDirectory.contains(currentFunc)) return;
        FuncInfo func = funcDirectory.getFunc(currentFunc);
        String paramName = ctx.ID().getText();
        SemanticCube.Type paramType = parseType(ctx.tipo());
        func.addParam(paramName, paramType);
    }

    // Punto neurálgico 4: parámetros adicionales (COMA ID : tipo ...)
    @Override
    public void enterParamsP(PatitoParser.ParamsPContext ctx) {
        if (ctx.ID() == null) return; // alternativa vacía
        if (currentFunc == null || !funcDirectory.contains(currentFunc)) return;
        FuncInfo func = funcDirectory.getFunc(currentFunc);
        String paramName = ctx.ID().getText();
        SemanticCube.Type paramType = parseType(ctx.tipo());
        func.addParam(paramName, paramType);
    }

    // VARIABLES

    // Punto neurálgico 5: cada línea de declaración  id1, id2 : tipo ; Se dispara tanto para vars globales como para vars locales de funciones
    @Override
    public void enterListDecl(PatitoParser.ListDeclContext ctx) {
        SemanticCube.Type type = parseType(ctx.tipo());
        List<String> ids = collectIds(ctx.listId());
        VarTable target = resolveVarTable();

        for (String id : ids) {
            if (!target.addVar(id, type)) {
                errors.add("SEMÁNTICO [línea " + ctx.tipo().getStart().getLine()
                    + "]: Variable doblemente declarada '" + id + "'");
            }
        }
    }

    // HELPERS

    // Si estamos dentro de una función: tabla local; si no tabla global
    private VarTable resolveVarTable() {
        if (currentFunc != null && funcDirectory.contains(currentFunc)) {
            return funcDirectory.getFunc(currentFunc).localVars;
        }
        return globalVarTable;
    }

    private List<String> collectIds(PatitoParser.ListIdContext ctx) {
        List<String> ids = new ArrayList<>();
        ids.add(ctx.ID().getText());
        collectIdsComa(ctx.listIdComa(), ids);
        return ids;
    }

    private void collectIdsComa(PatitoParser.ListIdComaContext ctx, List<String> ids) {
        if (ctx == null || ctx.ID() == null) return;
        ids.add(ctx.ID().getText());
        collectIdsComa(ctx.listIdComa(), ids);
    }

    private SemanticCube.Type parseType(PatitoParser.TipoContext ctx) {
        return ctx.ENTERO() != null ? SemanticCube.Type.ENTERO : SemanticCube.Type.FLOTANTE;
    }

    private SemanticCube.Type parseReturnType(PatitoParser.TipoOpcionalContext ctx) {
        if (ctx.NULA() != null) return null;
        return parseType(ctx.tipo());
    }

    // ACCESO PUBLICO

    public FuncDirectory getFuncDirectory()  { return funcDirectory; }
    public VarTable      getGlobalVarTable() { return globalVarTable; }
    public List<String>  getErrors()         { return errors; }
    public boolean       hasErrors()         { return !errors.isEmpty(); }
}
