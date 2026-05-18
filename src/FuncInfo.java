import java.util.ArrayList;
import java.util.List;

public class FuncInfo {
    public final String name;
    public final SemanticCube.Type returnType;
    public final List<VarInfo> params;   // orden importa para validar llamadas
    public final VarTable localVars;

    public FuncInfo(String name, SemanticCube.Type returnType) {
        this.name = name;
        this.returnType = returnType;
        this.params = new ArrayList<>();
        this.localVars = new VarTable();
    }

    public void addParam(String paramName, SemanticCube.Type paramType) {
        params.add(new VarInfo(paramName, paramType));
        // Los parámetros también son variables locales
        localVars.addVar(paramName, paramType);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("func ").append(name).append(" -> ").append(returnType).append("\n");
        sb.append("  params: ").append(params).append("\n");
        sb.append("  localVars:\n").append(localVars);
        return sb.toString();
    }
}
