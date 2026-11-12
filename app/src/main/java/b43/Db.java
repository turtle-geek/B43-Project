package b43;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class Db implements AutoCloseable {
    public static final class Result {
        public final List<String> columns;
        public final List<Object[]> rows;

        Result(List<String> columns, List<Object[]> rows) {
            this.columns = columns;
            this.rows = rows;
        }

        public boolean isEmpty() { return rows.isEmpty(); }
        public int size() { return rows.size(); }
        public Object get(int row, int col) { return rows.get(row)[col]; }
    }

    public interface TxWork<T> {
        T run() throws SQLException;
    }

    private final Connection conn;

    public Db(String url, String user, String password) throws SQLException {
        conn = DriverManager.getConnection(url, user, password);
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        return ps;
    }

    public Result query(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(sql, params);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<String> cols = new ArrayList<>();
            for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));
            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) {
                Object[] row = new Object[n];
                for (int i = 0; i < n; i++) row[i] = rs.getObject(i + 1);
                rows.add(row);
            }
            return new Result(cols, rows);
        }
    }

    public Object scalar(String sql, Object... params) throws SQLException {
        Result r = query(sql, params);
        return r.isEmpty() ? null : r.get(0, 0);
    }

    public boolean exists(String sql, Object... params) throws SQLException {
        return !query(sql, params).isEmpty();
    }

    public int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(sql, params)) {
            return ps.executeUpdate();
        }
    }

    public <T> T tx(TxWork<T> work) throws SQLException {
        conn.setAutoCommit(false);
        try {
            T result = work.run();
            conn.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
