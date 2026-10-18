# AstraxElementer – Elemental RPG System (Giai đoạn 1)

Plugin nguyên tố RPG cho server **BoxMine RPG** – Paper **1.21.4**, **Java 21**, Maven.

> **Trạng thái trung thực của bản này:** mã nguồn đầy đủ cho Giai đoạn 1, nhưng **chưa được build bằng Maven và chưa chạy thử trên server thật** (môi trường soạn thảo không có mạng để tải Paper API). Xem mục *Kiểm thử* và *Giới hạn đã biết*. Bạn cần chạy `mvn clean package` và thử trên server test trước khi đưa lên server chính.

## Tính năng đã triển khai (Giai đoạn 1)

- 6 nguyên tố: Hỏa, Băng, Lôi, Thổ, Phong, Quang – màu, biểu tượng, nội tại (attribute + giảm sát thương + proc đánh thường) cấu hình trong `elements.yml`.
- Thức tỉnh (GUI hoặc `/elementer awaken`), EXP, cấp 1–50, điểm kỹ năng, mở khóa kỹ năng theo cấp, nâng cấp từng kỹ năng bằng điểm.
- 18 kỹ năng có logic thật (sát thương/khống chế/hồi máu/khiên/lướt) + hiệu ứng hạt và âm thanh:
  Hỏa Cầu, Bùng Cháy, Thiên Hỏa · Băng Tiễn, Sương Giá, Băng Ngục · Lôi Bộ, Sét Đánh, Thiên Lôi · Thạch Giáp, Địa Chấn, Pháo Đài · Phong Bộ, Lưỡi Gió, Lốc Xoáy · Quang Thuẫn, Ánh Sáng Phục Hồi, Thánh Quang.
- Tương khắc vòng tròn Hỏa→Băng→Phong→Thổ→Lôi→Hỏa (Quang trung lập, mạnh hơn trước xác sống); áp dụng cả với quái (`mob-elements`).
- Giới hạn chống lạm dụng: trần sát thương mỗi đòn, hệ số PvP, hồi chiêu từng kỹ năng + hồi chiêu chung, trần thời gian khống chế + miễn nhiễm trói, trần đẩy lùi/khiên/hồi máu/lướt, tối đa số mục tiêu mỗi lần.
- EXP từ khai khoáng (bỏ qua khối do người chơi đặt), giết quái (bỏ qua quái từ spawner/trứng/golem tự dựng), PvP (cooldown cùng nạn nhân, bỏ qua cùng IP, giới hạn chênh cấp), có giới hạn EXP mỗi ngày.
- Phần thưởng mốc cấp chỉ nhận **một lần** cho mỗi người chơi (ghi nhận trong DB trước khi trao).
- SQLite (mọi thao tác DB chạy ở thread riêng, không chặn main thread), tải lỗi thì **không** ghi đè dữ liệu cũ; dữ liệu sai được sửa và ghi log.
- GUI: menu chính, thức tỉnh (bấm 2 lần xác nhận), kỹ năng (nâng cấp/chọn), chỉ số, tương khắc, bảng xếp hạng.

## Chưa có trong bản này (Giai đoạn 2–3)

MySQL, Element Mastery, tinh thể nguyên tố, thử thách, Boss, thành tựu, danh hiệu, ngoại hình/hào quang, nhánh nâng cấp theo nhánh, tổ hợp hai nguyên tố, tích hợp AstraxQuest / AstraxPetRPG / Vault / PlaceholderAPI, hỗ trợ BoxMine (đào nhanh, độ bền cuốc…), `/elementeradmin give crystal`. Cấu hình `database.type: mysql` hiện sẽ cảnh báo và dùng SQLite.

## Build

Yêu cầu: JDK 21, Maven 3.9+, truy cập mạng để tải `paper-api`.

```bash
mvn clean package
```

JAR: `target/AstraxElementer-1.0.0.jar`. Test chạy trong giai đoạn `package`; nếu cần bỏ qua: `-DskipTests`.

## Cài đặt

1. Dùng server Paper 1.21.4, Java 21.
2. Chép JAR vào `plugins/`, khởi động server.
3. Chỉnh `plugins/AstraxElementer/*.yml`, rồi `/elementeradmin reload`.

Paper đã kèm sẵn driver SQLite nên không cần thêm thư viện.

## Lệnh và quyền

| Lệnh | Quyền | Mặc định |
|---|---|---|
| `/elementer` | `elementer.command.main` | mọi người |
| `/elementer info` | `elementer.command.info` | mọi người |
| `/elementer skills` | `elementer.command.skills` | mọi người |
| `/elementer stats` | `elementer.command.stats` | mọi người |
| `/elementer top` | `elementer.command.top` | mọi người |
| `/elementer cast [1-3]` và phím F | `elementer.command.cast` | mọi người |
| `/elementer select <1-3>` | `elementer.command.select` | mọi người |
| `/elementer awaken <nguyên tố>` | `elementer.command.awaken` | mọi người |
| `/elementeradmin reload` | `elementer.admin.reload` | op |
| `/elementeradmin give <người chơi> <exp\|points> <số>` | `elementer.admin.give` | op |
| `/elementeradmin set <người chơi> <element\|level\|exp\|points> <giá trị>` | `elementer.admin.set` | op |
| `/elementeradmin reset <người chơi>` | `elementer.admin.reset` | op |

`elementer.admin.*` gồm toàn bộ quyền quản trị. Lệnh admin hiện chỉ áp dụng cho người chơi **đang online**.

**Cách dùng kỹ năng:** phím **F** (đổi tay) = tung kỹ năng đang chọn; **Shift+F** = đổi kỹ năng; hoặc `/elementer cast <1-3>`. Có thể tắt phím F trong `config.yml` (`input.swap-key-cast`).

## Cấu hình

| File | Nội dung |
|---|---|
| `config.yml` | DB, EXP/cấp, nguồn EXP, phần thưởng mốc cấp, giới hạn chiến đấu |
| `elements.yml` | Thông tin/nội tại 6 nguyên tố, tương khắc, nguyên tố của quái, danh sách xác sống |
| `skills.yml` | 18 kỹ năng: sát thương, tầm, bán kính, hồi chiêu, hiệu ứng, hạt, âm thanh |
| `messages.yml` | Toàn bộ thông báo và chữ trong GUI (hỗ trợ `&` và `&#rrggbb`) |

Mẹo cân bằng: sát thương thực = `damage × (1 + per-level-bonus×(cấp kỹ năng−1)) × (1 + damage-per-level×(cấp−1)) × tương khắc × (PvP 0.6)`, và luôn ≤ `combat.max-skill-damage`.

## Cập nhật

1. Sao lưu `plugins/AstraxElementer/` (đặc biệt `data.db`).
2. Thay JAR mới, khởi động lại.
3. File YAML hiện có **không bị ghi đè**; so sánh với bản mẫu trong JAR (giải nén) để thêm khóa mới. Khóa thiếu dùng giá trị mặc định (với `config.yml`) hoặc hiển thị `[thiếu tin nhắn: …]` (với `messages.yml`).

## Kiểm thử

Bộ test JUnit 5 (`src/test`): EXP/lên cấp, hồi chiêu, công thức sát thương + tương khắc, kiểm tra/sửa dữ liệu hỏng, lưu/tải SQLite, **chống nhận thưởng trùng** (kể cả 50 yêu cầu đồng thời), xếp hạng, khai báo quyền trong `plugin.yml`, và kiểm tra YAML mẫu (đủ 6×3 kỹ năng, trong trần).

Đã thực sự chạy ở môi trường soạn thảo: các test thuần logic (`ExpCurveTest`, `CooldownTrackerTest`, `DamageCalculatorTest`, `DataSanitizerTest` – 20 test đạt) và kiểm tra cú pháp toàn bộ mã nguồn. **Chưa chạy:** test SQLite, test YAML/quyền (cần Maven + thư viện) và mọi thứ cần server thật.

**Chưa có test tự động** cho các tình huống cần server (chết, thoát game, đổi thế giới, đổi mục tiêu, spam kỹ năng). Danh sách kiểm tra thủ công trên server test:

- [ ] Thức tỉnh → thoát → vào lại: nguyên tố/cấp/EXP còn nguyên.
- [ ] Spam phím F: chỉ tung theo hồi chiêu chung + hồi chiêu kỹ năng.
- [ ] Chết khi đang có khiên: khiên mất; hồi sinh: nội tại (máu/tốc độ…) áp dụng lại.
- [ ] Thoát giữa Thiên Lôi/Thiên Hỏa: không lỗi console, đòn tiếp theo không gây sát thương.
- [ ] Đổi thế giới giữa lúc đang cast: kỹ năng trễ bị hủy.
- [ ] Mục tiêu chết/đổi giữa chừng, người chơi creative/spectator, thế giới tắt PvP: không bị tác động.
- [ ] Đặt rồi đào lại quặng: không có EXP; đào quặng tự nhiên: có EXP; quái spawner: không có EXP.
- [ ] Đạt cấp 10/25/50 hai lần (reset rồi lên lại): phần thưởng chỉ trao một lần.
- [ ] Vùng WorldGuard cấm PvP: kỹ năng không gây sát thương/khống chế người chơi.

## Giới hạn đã biết

- Chưa biên dịch với Paper API thật: có thể còn lỗi biên dịch nhỏ ở các lớp dùng Bukkit (tên API đã viết theo Paper 1.21.4 nhưng chưa được compiler xác nhận). Gửi log `mvn` để sửa.
- Không có hệ thống party: **Ánh Sáng Phục Hồi** hồi cho mọi người chơi trong bán kính (có thể tắt bằng `effects.heal-others` trong `skills.yml`).
- Lá chắn trừ vào sát thương gốc trước giảm giáp, và hủy luôn đẩy lùi khi hấp thụ hết.
- Sát thương kỹ năng đi qua sự kiện sát thương chuẩn nên plugin bảo vệ vùng có thể hủy; khi đó khống chế/đẩy lùi cũng không áp dụng. Plugin chống gian lận có thể cảnh báo cú lướt (Lôi Bộ/Phong Bộ).
- Theo dõi khối do người chơi đặt nằm trong bộ nhớ (tối đa 50.000 khối gần nhất, mất khi restart); khối bị piston đẩy không được theo dõi.
- Phần thưởng mốc cấp chạy lệnh console từ `config.yml` – chỉ cấu hình lệnh bạn tin tưởng. Cấp được đặt bằng `/elementeradmin set level` không tự trao thưởng mốc.
- `/elementeradmin reset` xóa tiến trình nhưng **giữ** lịch sử nhận thưởng (để không nhận lại).
