package org.netbeans.lib.cvsclient.connection;

import org.netbeans.lib.cvsclient.io.IStreamLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Supplier;

/** Each request round owns a fresh transport (including SSH pool registration). */
public final class CommandConnection implements IConnection {
  private final Supplier<IConnection> factory;
  private final String repository;
  private IConnection current;

  public CommandConnection(String repository, Supplier<IConnection> factory) {
    this.repository = repository;
    this.factory = factory;
  }

  @Override public String getRepository() { return repository; }
  @Override public InputStream getInputStream() { return current.getInputStream(); }
  @Override public OutputStream getOutputStream() { return current.getOutputStream(); }
  @Override public void open(IStreamLogger logger) throws AuthenticationException {
    current = factory.get();
    try { current.open(logger); }
    catch (AuthenticationException | RuntimeException failure) {
      try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }
  @Override public void verify(IStreamLogger logger) throws AuthenticationException {
    factory.get().verify(logger);
  }
  @Override public void close() throws IOException {
    IConnection connection = current;
    current = null;
    if (connection != null) connection.close();
  }
}
