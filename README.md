# CoC Auto Helper (Android APK độc lập)

Ứng dụng Android độc lập hỗ trợ **Auto Farm (Nhà Chính & Nhà Thợ Xây)** và **Auto Nâng cấp (Công trình, Tường, Phòng Thí Nghiệm)** cho Clash of Clans trực tiếp trên điện thoại, **không cần PC, không cần Root**, sử dụng `AccessibilityService`, `MediaProjection` và OCR offline **Google ML Kit**.

> **Lưu ý quan trọng về rủi ro tài khoản:** Việc sử dụng công cụ tự động chạm / đọc màn hình vi phạm Điều khoản Dịch vụ (ToS) của Supercell và có thể dẫn đến cảnh báo hoặc khóa tài khoản. Chỉ nên sử dụng có kiểm soát, không treo 24/24 liên tục.

## Tính năng chính

1. **Auto Farm Nhà Chính (hỗ trợ cơ chế Clash Anytime):**
   - Thu hoạch mỏ Vàng / Dầu / Dầu Đen (quét bong bóng tài nguyên bằng phân tích màu HSV) và Xe cướp tài nguyên (Loot Cart).
   - Tìm nhà & lọc Vàng / Dầu / Dầu Đen bằng OCR offline (3 chế độ: Tổng Vàng+Dầu, Đạt tất cả, Đạt 1 trong 3), giới hạn lượt Next.
   - 3 chiến thuật thả quân tùy chỉnh: **Rải 4 cạnh**, **Dồn 1–2 hướng**, **Thả theo nhịp (nhiều đợt sóng)**; chọn ô Lính / ô Tướng (tự bật kỹ năng) / ô Phép; tự bỏ qua ô hết quân; tùy chọn đầu hàng sớm.
2. **Auto Farm Nhà Thợ Xây (Builder Base):**
   - Chạy riêng hoặc luân phiên với Nhà Chính (tự chuyển thuyền).
   - Thu hoạch tài nguyên, tự kích hoạt Tháp Đồng Hồ (Clock Tower), spam kỹ năng Battle Machine/Copter, hỗ trợ chế độ **Farm nhanh (Fast Surrender)** hoặc đánh hết trận.
3. **Auto Nâng cấp thông minh:**
   - **Ngưỡng tài nguyên tối thiểu giữ lại** (Vàng, Dầu, Dầu Đen) — không nâng nếu sau khi trừ chi phí bị tụt dưới ngưỡng.
   - **Tự nâng công trình** qua menu Gợi ý Thợ Xây (OCR đọc tên + giá + màu loại tài nguyên), giữ riêng `N` thợ cho Tường, chọn thứ tự ưu tiên (Rẻ nhất / Đắt nhất / Đầu danh sách), và **bộ lọc từ khóa tránh nâng** (hỗ trợ cả tiếng Việt không dấu/có dấu & tiếng Anh như `Town Hall`, `Nhà Chính`, `Hero`, `Wall`...).
   - **Tự nâng Tường (Walls)** khi Vàng hoặc Dầu vượt ngưỡng cấu hình.
   - **Tự nâng Phòng Thí Nghiệm (Laboratory)** theo chu kỳ, hỗ trợ ưu tiên Dầu Đen và danh sách từ khóa tránh nâng.
4. **Giữ phiên & Khôi phục:**
   - Tự mở lại Clash of Clans khi bị văng, tự bấm `Reload / Tải lại` khi mất kết nối, nghỉ an toàn khi gặp `Personal Break / Thiết bị khác`, giữ CPU không ngủ (`WakeLock`).
5. **Thanh điều khiển nổi (Floating HUD) & Lớp phủ Hiệu chỉnh tọa độ trực tiếp trên game:**
   - Kéo khung OCR và chấm nút trực tiếp lên giao diện thật của Clash of Clans để tương thích 100% với mọi tỷ lệ màn hình điện thoại (`16:9`, `19.5:9`, `20:9`, tai thỏ/đục lỗ).
   - Có nút **Thử OCR** trực tiếp để kiểm tra kết quả đọc ngay trên màn hình game và **Sao lưu / Khôi phục JSON**.

## Cài đặt file APK lên điện thoại

1. Copy file APK đã build tại:
   `app/build/outputs/apk/debug/app-debug.apk`
   sang điện thoại Android (Android 8.0 / API 26 trở lên) và cài đặt (cho phép cài từ nguồn không xác định).
2. Mở **CoC Auto Helper** → tab **Tổng quan** và cấp đủ 4 quyền:
   - **Dịch vụ Trợ năng (AccessibilityService)**: Bật mục *CoC Auto - Điều khiển cảm ứng* (nếu Android 13+ báo "Cài đặt bị hạn chế", vào *Thông tin ứng dụng → dấu 3 chấm góc trên phải → Cho phép cài đặt bị hạn chế* rồi quay lại bật Trợ năng).
   - **Chụp màn hình (MediaProjection)**: Bấm *Cấp quyền* → chọn *Toàn màn hình*.
   - **Thông báo nền** & **Tắt giới hạn pin**.
3. Chỉnh ngưỡng farm & nâng cấp ở tab **Auto Farm** và **Nâng cấp**.
4. Bấm **"Mở khung hiệu chỉnh tọa độ trực tiếp trên game"** để căn khớp các khung OCR (`Loot địch`, `Vàng nhà`, `Dầu nhà`, `Thợ xây`, `Menu nâng cấp`, `Thanh lính`) và các nút (`Đánh`, `Tìm trận`, `Tiếp`, `Kết thúc`, `Về nhà`, 4 đỉnh khung thả quân) đúng với màn hình máy bạn → bấm **Thử OCR** để kiểm tra → bấm **Lưu**.
5. Bấm **Bắt đầu Auto** (hoặc điều khiển trực tiếp từ thanh nổi trên game).

## Build lại từ mã nguồn (Windows PowerShell)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat testDebugUnitTest assembleDebug
```

## Tự động cập nhật qua GitHub Releases (`ledinhtien219/COCAUTO`)

Ứng dụng đã được cấu hình sẵn nguồn cập nhật mặc định là **`ledinhtien219/COCAUTO`**.

- Mỗi khi phát hành bản mới, chỉ cần đẩy mã nguồn và gắn tag `vX.Y.Z` lên GitHub:
  ```powershell
  git add .
  git commit -m "Release v1.2.1"
  git push -u origin main
  git tag v1.2.1
  git push origin v1.2.1
  ```
- Workflow `.github/workflows/release.yml` sẽ tự động chạy unit test, build file APK và tạo **GitHub Release** đính kèm `CoC-Auto-v1.2.1.apk`.
- Điện thoại đã cài app sẽ tự phát hiện bản mới ngay khi mở app và cho phép tải + cài đặt với 1 chạm.
- Ngoài ra, có thể cập nhật trực tiếp qua Wi-Fi nội bộ từ máy tính bằng lệnh `node .\scripts\serve-update.mjs`.
