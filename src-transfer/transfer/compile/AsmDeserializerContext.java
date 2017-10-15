package transfer.compile;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import utils.enhance.asm.util.AsmUtils;

/**
 * 预编译解码器上下文
 * @author Jake
 *
 */
public class AsmDeserializerContext implements Opcodes {

    /**
     * 类名
     */
    private final String className;

    /**
     * ClassWriter
     */
    private final ClassWriter classWriter;

    /**
     * 自增的方法Id
     */
    private int methodId = 1;

    /**
     * 类型 -- 方编译好的方法
     */
    private Map<Type, String> methodCache = new HashMap<Type, String>();


    /**
     * 构造方法
     * @param className
     * @param classWriter
     */
    public AsmDeserializerContext(String className, ClassWriter classWriter) {
        this.className = className;
        this.classWriter = classWriter;
    }


    /**
     * 执行下一个编码方法
     * @return
     * @param name
     * @param curMethodVisitor 
     */
    public MethodVisitor invokeNextDeserialize(Type type, String name, MethodVisitor curMethodVisitor) {

        if (name == null) {
            name = "default";
        }

        String existsMethodName = getCompileMethod(type);
        String newMethodName = existsMethodName;
        if (newMethodName  == null) {
            newMethodName = "deserialze_" + name + "_" + (methodId ++);
            this.addCompileMethod(type, newMethodName);
        }

        if (existsMethodName == null) {
            MethodVisitor mv = classWriter.visitMethod(ACC_PUBLIC, newMethodName,
                "(Ltransfer/Inputable;Ljava/lang/reflect/Type;BLtransfer/core/DeserialContext;)Ljava/lang/Object;",
                "<T:Ljava/lang/Object;>(Ltransfer/Inputable;Ljava/lang/reflect/Type;BLtransfer/core/DeserialContext;)TT;",

                null);
            mv.visitCode();

            curMethodVisitor.visitMethodInsn(INVOKEVIRTUAL, AsmUtils.toAsmCls(className), newMethodName,
                "(Ltransfer/Inputable;Ljava/lang/reflect/Type;BLtransfer/core/DeserialContext;)Ljava/lang/Object;",
                false);
            return mv;
        } else {
            curMethodVisitor.visitMethodInsn(INVOKEVIRTUAL, AsmUtils.toAsmCls(className), newMethodName,
                "(Ltransfer/Inputable;Ljava/lang/reflect/Type;BLtransfer/core/DeserialContext;)Ljava/lang/Object;",
                false);
            return null;
        }
    }

    public void addCompileMethod(Type type, String methodName) {
        methodCache.put(type, methodName);
    }

    public String getCompileMethod(Type type) {
        return methodCache.get(type);
    }
    		
	public ClassWriter getClassWriter() {
		return classWriter;
	}


    public String getClassName() {
        return className;
    }
	
	
}
