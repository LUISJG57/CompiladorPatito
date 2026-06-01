import java.util.ArrayList;
import java.util.List;

public class FuncInfo {
    public final String name;
    public final SemanticCube.Type returnType;     // null = nula (void)
    public final List<VarInfo> params;             // firma: orden + tipos para validar llamadas
    public final VarTable localVars;

    // ── Entrega 4: recursos para ejecución ──────────────────────────────
    public int startQuad = -1;   // CONT: cuádruplo donde inicia la función (destino de GOSUB)
    public int eraSize   = 0;    // tamaño del Activation Record (locales + temporales)
    public int localsInt = 0, localsFloat = 0, tempsInt = 0, tempsFloat = 0;

    public FuncInfo(String name, SemanticCube.Type returnType) {
        this.name = name;
        this.returnType = returnType;
        this.params = new ArrayList<>();
        this.localVars = new VarTable();
    }

    // El listener asigna la dirección local (vm.nextLocal) y la pasa aquí.
    public void addParam(String paramName, SemanticCube.Type paramType, int address) {
        params.add(new VarInfo(paramName, paramType, address));
        // Los parámetros también son variables locales
        localVars.addVar(paramName, paramType, address);
    }

    public void setResources(int localsInt, int localsFloat, int tempsInt, int tempsFloat) {
        this.localsInt   = localsInt;
        this.localsFloat = localsFloat;
        this.tempsInt    = tempsInt;
        this.tempsFloat  = tempsFloat;
        this.eraSize     = localsInt + localsFloat + tempsInt + tempsFloat;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("func ").append(name).append(" -> ").append(returnType)
          .append("  [startQuad=").append(startQuad)
          .append(", eraSize=").append(eraSize)
          .append(" (locInt=").append(localsInt).append(", locFlt=").append(localsFloat)
          .append(", tmpInt=").append(tempsInt).append(", tmpFlt=").append(tempsFloat).append(")]\n");
        sb.append("  params: ").append(params).append("\n");
        sb.append("  localVars:\n").append(localVars);
        return sb.toString();
    }
}
