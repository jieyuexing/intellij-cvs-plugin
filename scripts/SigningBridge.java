import java.util.jar.JarFile;
import java.util.ArrayList;
import java.util.List;

/** 口令只从子进程环境读取；只在 JVM 内传给官方 CLI，不进入 OS argv。 */
class SigningBridge {
    public static void main(String[] args) throws Exception {
        String password = System.getenv("OPENCVS_SIGNING_PASSWORD");
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("缺少签名口令环境变量");
        }
        // 官方 CLI 的异常和输出可能含参数；签名结果由单独验签步骤确认。
        var output = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        try (JarFile jar = new JarFile(args[0])) {
            String main = jar.getManifest().getMainAttributes().getValue("Main-Class");
            List<String> parameters = new ArrayList<>(List.of("sign", "-in", args[1], "-out", args[2],
                    "-key-file", args[3], "-cert-file", args[4], "-key-pass", password));
            Class.forName(main).getMethod("main", String[].class)
                    .invoke(null, (Object) parameters.toArray(String[]::new));
            output.println("签名桥接完成；必须继续验签。");
        } catch (Throwable error) {
            output.println("签名失败；秘密诊断已抑制。");
            System.exit(1);
        }
    }
}
