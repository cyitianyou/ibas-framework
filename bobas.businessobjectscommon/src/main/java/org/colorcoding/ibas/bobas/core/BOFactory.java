package org.colorcoding.ibas.bobas.core;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Vector;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.colorcoding.ibas.bobas.MyConfiguration;
import org.colorcoding.ibas.bobas.configuration.Configuration;
import org.colorcoding.ibas.bobas.data.ArrayList;
import org.colorcoding.ibas.bobas.mapping.BOCode;
import org.colorcoding.ibas.bobas.message.Logger;
import org.colorcoding.ibas.bobas.message.MessageLevel;

/**
 * 业务对象工厂
 * 
 * 注意：使用BOCode获取Class需要提前加载命名空间的类
 * 
 * @author Niuren.Zhu
 *
 */
public class BOFactory implements IBOFactory {

	protected static final String MSG_BO_FACTORY_REGISTER_BO_CODE = "factory: register [%s] for [%s].";

	volatile private static IBOFactory instance = null;

	public static IBOFactory create() {
		if (instance == null) {
			synchronized (BOFactory.class) {
				if (instance == null) {
					instance = new BOFactory();
				}
			}
		}
		return instance;
	}

	private BOFactory() {

	}

	protected ClassLoader getClassLoader() {
		return Thread.currentThread().getContextClassLoader();
	}

	@Override
	public void loadPackage(String path) throws IOException {
		URL url = null;
		String fullPath = path;
		if (fullPath != null && (fullPath.indexOf(File.separator) < 0)) {
			// 不是完整路径(目录 分隔符 文件名称)
			fullPath = String.format("%s%s%s", Configuration.getWorkFolder(), File.separator, fullPath);
		}
		url = new File(path).toURI().toURL();
		this.loadPackage(url);
	}

	@Override
	public void loadPackage(URL url) throws IOException {
		URLClassLoader classLoader = new URLClassLoader(new URL[] { url }, this.getClassLoader());
		classLoader.close();
	}

	@Override
	public <P> P createInstance(Class<P> type) throws InstantiationException, IllegalAccessException {
		if (type == null) {
			return null;
		}
		return type.newInstance();
	}

	@Override
	public Object createInstance(String className)
			throws ClassNotFoundException, InstantiationException, IllegalAccessException {
		Class<?> type = this.getClass(className);
		return type.newInstance();
	}

	@Override
	public Class<?> loadClass(String name) throws ClassNotFoundException {
		return Class.forName(name, true, this.getClassLoader());
	}

	@Override
	public Class<?>[] getClasses(String packageName) {
		if (packageName == null || packageName.isEmpty()) {
			return new Class<?>[] {};
		}
		ArrayList<Class<?>> classes = new ArrayList<>();
		ClassLoader classLoader = this.getClassLoader();
		Class<?> rootClass = classLoader.getClass();
		while (rootClass != ClassLoader.class)
			rootClass = rootClass.getSuperclass();
		if (rootClass != null) {
			try {
				// 反射根类型，并设置已加载类可见
				Field field = rootClass.getDeclaredField("classes");
				field.setAccessible(true);
				// 获取已加载的类
				Vector<?> v = (Vector<?>) field.get(classLoader);
				// 遍历并分析类型
				for (int i = 0; i < v.size(); i++) {
					Class<?> type = (Class<?>) v.get(i);
					if (!type.getName().startsWith(packageName))
						continue;
					classes.add(type);
				}
				field.setAccessible(false);
			} catch (Exception e) {
			}
		}
		return classes.toArray(new Class<?>[] {});
	}

	@Override
	public Class<?>[] loadClasses(String packageName) {
		// 第一个class类的集合
		Set<Class<?>> classes = new LinkedHashSet<Class<?>>();
		String packageDirName = packageName.replace('.', '/');
		// 定义一个枚举的集合 并进行循环来处理这个目录下的things
		Enumeration<URL> dirs;
		try {
			ClassLoader classLoader = this.getClassLoader();
			dirs = classLoader.getResources(packageDirName);
			// 循环迭代下去
			while (dirs.hasMoreElements()) {
				// 获取下一个元素
				URL url = dirs.nextElement();
				// 得到协议的名称
				String protocol = url.getProtocol();
				// 如果是以文件的形式保存在服务器上
				if ("file".equals(protocol)) {
					// 获取包的物理路径
					String filePath = URLDecoder.decode(url.getFile(), "UTF-8");
					// 以文件的方式扫描整个包下的文件 并添加到集合中
					this.findClassesInPackageByFile(packageName, filePath, classes);
				} else if ("jar".equals(protocol)) {
					// 如果是jar包文件
					// 定义一个JarFile
					JarFile jar;
					try {
						// 获取jar
						jar = ((JarURLConnection) url.openConnection()).getJarFile();
						// 从此jar包 得到一个枚举类
						Enumeration<JarEntry> entries = jar.entries();
						// 同样的进行循环迭代
						while (entries.hasMoreElements()) {
							// 获取jar里的一个实体 可以是目录 和一些jar包里的其他文件 如META-INF等文件
							JarEntry entry = entries.nextElement();
							String name = entry.getName();
							// 如果是以/开头的
							if (name.charAt(0) == '/') {
								// 获取后面的字符串
								name = name.substring(1);
							}
							// 如果前半部分和定义的包名相同
							if (name.startsWith(packageDirName)) {
								int idx = name.lastIndexOf('/');
								// 如果以"/"结尾 是一个包
								if (idx != -1) {
									// 获取包名 把"/"替换成"."
									packageName = name.substring(0, idx).replace('/', '.');
								}
								// 如果可以迭代下去 并且是一个包
								if (idx != -1) {
									// 如果是一个.class文件 而且不是目录
									if (name.endsWith(".class") && !entry.isDirectory()) {
										// 去掉后面的".class" 获取真正的类名
										String className = name.substring(packageName.length() + 1, name.length() - 6);
										try {
											// 添加到classes
											classes.add(classLoader.loadClass(packageName + '.' + className));
										} catch (ClassNotFoundException e) {
											// Logger.log(MessageLevel.DEBUG, e);
										}
									}
								}
							}
						}
					} catch (IOException e) {
						Logger.log(MessageLevel.DEBUG, e);
					}
				}
			}
		} catch (IOException e) {
			Logger.log(MessageLevel.DEBUG, e);
		}
		return classes.toArray(new Class<?>[] {});
	}

	private void findClassesInPackageByFile(String packageName, String packagePath, Set<Class<?>> classes) {
		// 获取此包的目录 建立一个File
		File dir = new File(packagePath);
		// 如果不存在或者 也不是目录就直接返回
		if (!dir.exists() || !dir.isDirectory()) {
			return;
		}
		// 如果存在 就获取包下的所有文件 包括目录
		File[] dirfiles = dir.listFiles(new FileFilter() {
			// 自定义过滤规则 如果可以循环(包含子目录) 或则是以.class结尾的文件(编译好的java类文件)
			public boolean accept(File file) {
				return (file.isDirectory()) || (file.getName().endsWith(".class"));
			}
		});
		if (dirfiles != null) {
			ClassLoader classLoader = this.getClassLoader();
			// 循环所有文件
			for (File file : dirfiles) {
				// 如果是目录 则继续扫描
				if (file.isDirectory()) {
					findClassesInPackageByFile(packageName + "." + file.getName(), file.getAbsolutePath(), classes);
				} else {
					// 如果是java类文件 去掉后面的.class 只留下类名
					String className = file.getName().substring(0, file.getName().length() - 6);
					try {
						// 添加到集合中去
						// 这里用forName有一些不好，会触发static方法，没有使用classLoader的load干净
						classes.add(classLoader.loadClass(packageName + '.' + className));
					} catch (ClassNotFoundException e) {
						// Logger.log(MessageLevel.DEBUG, e);
					}
				}
			}
		}
	}

	@Override
	public String getCode(Class<?> type) {
		if (type == null) {
			return null;
		}
		Annotation annotation = type.getAnnotation(BOCode.class);
		if (annotation != null) {
			return MyConfiguration.applyVariables(((BOCode) annotation).value());
		}
		return null;
	}

	private volatile HashMap<String, String> classMap;

	/**
	 * boCode对应的类名称
	 * 
	 * 不缓存Class，避免引用导CG不回收未使用的资源
	 * 
	 * @return
	 */
	protected HashMap<String, String> getClassMap() {
		if (this.classMap == null) {
			synchronized (this) {
				if (this.classMap == null) {
					this.classMap = new HashMap<String, String>();
				}
			}
		}
		return this.classMap;
	}

	@Override
	public void register(String boCode, String className) {
		if (boCode == null || boCode.isEmpty()) {
			return;
		}
		if (className == null || className.isEmpty()) {
			return;
		}
		this.getClassMap().put(boCode, className);
		Logger.log(MessageLevel.DEBUG, MSG_BO_FACTORY_REGISTER_BO_CODE, className, boCode);
	}

	@Override
	public boolean register(Class<?> type) {
		String boCode = this.getCode(type);
		if (boCode != null && !boCode.isEmpty()) {
			this.register(boCode, type);
			return true;
		}
		return false;
	}

	@Override
	public void register(String boCode, Class<?> type) {
		if (type == null) {
			return;
		}
		this.register(boCode, type.getName());
	}

	@Override
	public Class<?> getClass(String boCode) throws ClassNotFoundException {
		String type = this.getClassMap().get(boCode);
		if (type != null) {
			// 已缓存，加载类
			return this.loadClass(type);
		}
		throw new ClassNotFoundException(boCode);
	}

}
