package com.astrax.elementer.storage;

import com.astrax.elementer.core.PlayerSnapshot;
import com.astrax.elementer.core.TopEntry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lưu trữ SQLite. Mọi thao tác chạy trên MỘT thread riêng (tuần tự), nên không bao giờ chặn main thread
 * và các thao tác của cùng một người chơi luôn đúng thứ tự (ví dụ lưu lúc thoát rồi mới tải lúc vào lại).
 */
public final class ElementStorage implements AutoCloseable {
    private final String url;
    private final Logger log;
    private final ExecutorService exec;
    private Connection conn; // chỉ dùng trong thread của exec

    public ElementStorage(String jdbcUrl, Logger log) {
        this.url = jdbcUrl;
        this.log = log;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AstraxElementer-DB");
            t.setDaemon(true);
            return t;
        });
    }

    // ---------------------------------------------------------------- API bất đồng bộ

    public CompletableFuture<Void> init() {
        return CompletableFuture.runAsync(() -> run(this::initSync), exec);
    }

    public CompletableFuture<PlayerSnapshot> load(UUID id) {
        return CompletableFuture.supplyAsync(() -> call(() -> loadSync(id)), exec);
    }

    public CompletableFuture<Void> save(PlayerSnapshot s) {
        return CompletableFuture.runAsync(() -> run(() -> saveSync(s)), exec);
    }

    /** @return true nếu đây là lần đầu nhận phần thưởng này (đã ghi nhận), false nếu đã nhận trước đó. */
    public CompletableFuture<Boolean> claimReward(UUID id, String rewardKey) {
        return CompletableFuture.supplyAsync(() -> call(() -> claimSync(id, rewardKey)), exec);
    }

    /** Hoàn tác việc ghi nhận (dùng khi trao thưởng thất bại, ví dụ người chơi đã thoát). */
    public CompletableFuture<Void> unclaimReward(UUID id, String rewardKey) {
        return CompletableFuture.runAsync(() -> run(() -> unclaimSync(id, rewardKey)), exec);
    }

    public CompletableFuture<List<TopEntry>> top(int limit) {
        return CompletableFuture.supplyAsync(() -> call(() -> topSync(limit)), exec);
    }

    @Override
    public void close() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warning("Hàng đợi lưu dữ liệu chưa xong sau 15 giây.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
            // đang đóng, bỏ qua
        }
    }

    // ---------------------------------------------------------------- Triển khai (thread DB)

    private interface SqlSupplier<T> { T get() throws SQLException; }
    private interface SqlRunnable { void run() throws SQLException; }

    private void run(SqlRunnable r) {
        call(() -> { r.run(); return null; });
    }

    private <T> T call(SqlSupplier<T> s) {
        try {
            return s.get();
        } catch (SQLException e) {
            log.log(Level.SEVERE, "Lỗi cơ sở dữ liệu AstraxElementer: " + e.getMessage(), e);
            throw new StorageException(e);
        }
    }

    /** Lỗi lưu trữ (bọc SQLException để dùng trong CompletableFuture). */
    public static final class StorageException extends RuntimeException {
        public StorageException(Throwable cause) { super(cause); }
    }

    private Connection connection() throws SQLException {
        if (conn == null || conn.isClosed() || !conn.isValid(2)) {
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new SQLException("Không tìm thấy driver SQLite (org.sqlite.JDBC)", e);
            }
            conn = DriverManager.getConnection(url);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
            }
        }
        return conn;
    }

    private void initSync() throws SQLException {
        try (Statement st = connection().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS players ("
                    + "uuid TEXT PRIMARY KEY,"
                    + "name TEXT,"
                    + "element TEXT,"
                    + "level INTEGER NOT NULL DEFAULT 1,"
                    + "exp INTEGER NOT NULL DEFAULT 0,"
                    + "skill_points INTEGER NOT NULL DEFAULT 0,"
                    + "selected_slot INTEGER NOT NULL DEFAULT 1,"
                    + "daily_exp INTEGER NOT NULL DEFAULT 0,"
                    + "daily_day INTEGER NOT NULL DEFAULT 0,"
                    + "awakened_at INTEGER NOT NULL DEFAULT 0,"
                    + "skills TEXT NOT NULL DEFAULT '',"
                    + "updated_at INTEGER NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS claimed_rewards ("
                    + "uuid TEXT NOT NULL,"
                    + "reward_key TEXT NOT NULL,"
                    + "claimed_at INTEGER NOT NULL,"
                    + "PRIMARY KEY (uuid, reward_key))");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_players_rank ON players(level DESC, exp DESC)");
        }
    }

    private PlayerSnapshot loadSync(UUID id) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT name, element, level, exp, skill_points, selected_slot, daily_exp, daily_day, awakened_at, skills "
                        + "FROM players WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new PlayerSnapshot(id, rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4),
                        rs.getInt(5), rs.getInt(6), rs.getLong(7), rs.getLong(8), rs.getLong(9),
                        parseSkills(rs.getString(10)));
            }
        }
    }

    private void saveSync(PlayerSnapshot s) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "INSERT INTO players (uuid, name, element, level, exp, skill_points, selected_slot, daily_exp, daily_day, "
                        + "awakened_at, skills, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, element=excluded.element, level=excluded.level, "
                        + "exp=excluded.exp, skill_points=excluded.skill_points, selected_slot=excluded.selected_slot, "
                        + "daily_exp=excluded.daily_exp, daily_day=excluded.daily_day, awakened_at=excluded.awakened_at, "
                        + "skills=excluded.skills, updated_at=excluded.updated_at")) {
            ps.setString(1, s.uuid().toString());
            ps.setString(2, s.name());
            ps.setString(3, s.element());
            ps.setInt(4, s.level());
            ps.setLong(5, s.exp());
            ps.setInt(6, s.skillPoints());
            ps.setInt(7, s.selectedSlot());
            ps.setLong(8, s.dailyExp());
            ps.setLong(9, s.dailyDay());
            ps.setLong(10, s.awakenedAt());
            ps.setString(11, serializeSkills(s.skills()));
            ps.setLong(12, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private boolean claimSync(UUID id, String key) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "INSERT OR IGNORE INTO claimed_rewards (uuid, reward_key, claimed_at) VALUES (?,?,?)")) {
            ps.setString(1, id.toString());
            ps.setString(2, key);
            ps.setLong(3, System.currentTimeMillis());
            return ps.executeUpdate() == 1;
        }
    }

    private void unclaimSync(UUID id, String key) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "DELETE FROM claimed_rewards WHERE uuid = ? AND reward_key = ?")) {
            ps.setString(1, id.toString());
            ps.setString(2, key);
            ps.executeUpdate();
        }
    }

    private List<TopEntry> topSync(int limit) throws SQLException {
        List<TopEntry> out = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT uuid, name, element, level, exp FROM players WHERE element IS NOT NULL "
                        + "ORDER BY level DESC, exp DESC, name ASC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id;
                    try {
                        id = UUID.fromString(rs.getString(1));
                    } catch (IllegalArgumentException e) {
                        continue; // dòng hỏng, bỏ qua
                    }
                    out.add(new TopEntry(id, rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5)));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Tuần tự hóa kỹ năng

    static String serializeSkills(Map<String, Integer> skills) {
        if (skills == null || skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : skills.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Đọc "id=lv;id=lv". Bỏ qua mục sai định dạng thay vì ném lỗi. */
    static Map<String, Integer> parseSkills(String raw) {
        Map<String, Integer> m = new HashMap<>();
        if (raw == null || raw.isBlank()) return m;
        for (String part : raw.split(";")) {
            int i = part.indexOf('=');
            if (i <= 0 || i == part.length() - 1) continue;
            try {
                m.put(part.substring(0, i).trim(), Integer.parseInt(part.substring(i + 1).trim()));
            } catch (NumberFormatException ignored) {
                // mục hỏng
            }
        }
        return m;
    }
}
