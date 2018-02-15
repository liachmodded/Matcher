package matcher.srcprocess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.nodeTypes.NodeWithParameters;
import com.github.javaparser.ast.nodeTypes.NodeWithType;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VoidType;

import matcher.type.ClassInstance;
import matcher.type.FieldInstance;
import matcher.type.IClassEnv;
import matcher.type.IMatchable;
import matcher.type.MethodInstance;

class TypeResolver {
	public void setup(ClassInstance rootCls, boolean mapped, CompilationUnit cu) {
		this.rootCls = rootCls;
		this.env = rootCls.getEnv();
		this.mapped = mapped;

		if (cu.getPackageDeclaration().isPresent()) {
			pkg = cu.getPackageDeclaration().get().getNameAsString().replace('.', '/');
			wildcardImports.add(pkg.concat("/"));
		} else {
			pkg = null;
			wildcardImports.add("");
		}

		wildcardImports.add("java/lang/");

		for (ImportDeclaration imp : cu.getImports()) {
			if (imp.isStatic()) continue;

			if (imp.isAsterisk()) {
				wildcardImports.add(imp.getNameAsString().replace('.', '/').concat("/"));
			} else {
				String name = imp.getNameAsString();
				int pos = name.lastIndexOf('.');

				if (pos != -1) imports.put(name.substring(pos + 1), name.replace('.', '/'));
			}
		}
	}

	public ClassInstance getCls(Node node) {
		do {
			if (node instanceof ClassOrInterfaceDeclaration || node instanceof EnumDeclaration) {
				TypeDeclaration<?> typeDecl = (TypeDeclaration<?>) node;

				String name = typeDecl.getName().getIdentifier();
				if (pkg != null) name = pkg+"/"+name;

				ClassInstance cls = getClsByName(name);

				return cls;
			}
		} while ((node = node.getParentNode().orElse(null)) != null);

		throw new IllegalStateException();
	}

	public <T extends CallableDeclaration<T> & NodeWithParameters<T>> MethodInstance getMethod(T methodDecl) {
		ClassInstance cls = getCls(methodDecl);
		if (cls == null) return null;

		StringBuilder sb = new StringBuilder();
		sb.append('(');

		if (methodDecl instanceof ConstructorDeclaration && cls.isEnum()) { // implicit extra String name + int ordinal for enum constructors
			sb.append("Ljava/lang/String;I");
		}

		for (Parameter p : methodDecl.getParameters()) {
			sb.append(toDesc(p.getType()));
		}

		sb.append(')');

		if (methodDecl instanceof NodeWithType) {
			sb.append(toDesc(((NodeWithType<?, ?>) methodDecl).getType()));
		} else {
			sb.append('V');
		}

		String desc = sb.toString();
		String name;

		if (methodDecl instanceof ConstructorDeclaration) {
			name = "<init>";
		} else {
			name = methodDecl.getName().getIdentifier();
		}

		return mapped ? cls.getMappedMethod(name, desc) : cls.getMethod(name, desc);
	}

	public FieldInstance getField(FieldDeclaration fieldDecl, int var) {
		ClassInstance cls = getCls(fieldDecl);
		if (cls == null) return null;

		VariableDeclarator varDecl = fieldDecl.getVariable(var);

		String name = varDecl.getName().getIdentifier();
		String desc = toDesc(varDecl.getType());

		return mapped ? cls.getMappedField(name, desc) : cls.getField(name, desc);
	}

	private String toDesc(Type type) {
		if (type instanceof PrimitiveType) {
			PrimitiveType t = (PrimitiveType) type;

			switch (t.getType()) {
			case BOOLEAN: return "Z";
			case BYTE: return "B";
			case CHAR: return "C";
			case DOUBLE: return "D";
			case FLOAT: return "F";
			case INT: return "I";
			case LONG: return "J";
			case SHORT: return "S";
			default:
				throw new IllegalArgumentException("invalid primitive type class: "+t.getType().getClass().getName());
			}
		} else if (type instanceof VoidType) {
			return "V";
		} else if (type instanceof ClassOrInterfaceType) {
			ClassOrInterfaceType t = (ClassOrInterfaceType) type;
			String name;

			if (!t.getScope().isPresent()) {
				name = t.getNameAsString();
			} else {
				List<String> parts = new ArrayList<>();

				do {
					parts.add(t.getName().getIdentifier());
				} while ((t = t.getScope().orElse(null)) != null);

				StringBuilder sb = new StringBuilder();

				for (int i = parts.size() - 1; i >= 0; i--) {
					if (sb.length() > 1) sb.append('/');
					sb.append(parts.get(i));
				}

				name = sb.toString();
			}

			assert name.indexOf('.') == -1;

			int pkgEnd = name.lastIndexOf('/');

			for (;;) {
				String cName = name.substring(pkgEnd + 1).replace('/', '$');

				if (pkgEnd == -1) {
					String importedName = imports.get(name);

					if (importedName != null) {
						return ClassInstance.getId(importedName);
					}

					for (String wildcardImport : wildcardImports) {
						String fullName = wildcardImport.concat(cName);
						ClassInstance cls = getClsByName(fullName);
						if (cls != null) return ClassInstance.getId(fullName);
					}

					break;
				}

				cName = name.substring(0, pkgEnd + 1).concat(cName);

				ClassInstance cls = getClsByName(cName);
				if (cls != null) ClassInstance.getId(cName);

				pkgEnd = name.lastIndexOf('/', pkgEnd - 1);
			}

			return ClassInstance.getId(name);
		} else if (type instanceof ArrayType) {
			ArrayType t = (ArrayType) type;
			Type componentType = t.getComponentType();
			int dims = 1;

			while (componentType instanceof ArrayType) {
				componentType = ((ArrayType) componentType).getComponentType();
				dims++;
			}

			StringBuilder ret = new StringBuilder();
			for (int i = 0; i < dims; i++) ret.append('[');
			ret.append(toDesc(componentType));

			return ret.toString();
		} else {
			throw new IllegalArgumentException("invalid type class: "+type.getClass().getName());
		}
	}

	public ClassInstance getClsByName(String name) {
		return mapped ? env.getClsByMappedName(name) : env.getClsByName(name);
	}

	public String getName(IMatchable<?> e) {
		return mapped ? e.getMappedName(true) : e.getName();
	}

	private ClassInstance rootCls;
	private IClassEnv env;
	private boolean mapped;
	private String pkg;
	private final Map<String, String> imports = new HashMap<>();
	private final List<String> wildcardImports = new ArrayList<>();
}