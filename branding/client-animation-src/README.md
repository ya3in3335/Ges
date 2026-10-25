# موشن شعار التنسيقية — الملفات المصدرية

## التشغيل السريع
افتح `oran-motion.html` في أي متصفح (Chrome / Edge / Firefox). الملف مستقل بالكامل: الطبقات مضمّنة داخله، ولا يحتاج إنترنت إلا للخطوط.

## محتوى الحزمة
- `oran-motion.html` — الموشن كامل (Canvas + JavaScript)، فيه التشغيل والخط الزمني والمقاسات 16:9 / 1:1 / 9:16.
- `layers/` — الطبقات الـ12 مقصوصة PNG شفافة بدقة 2x (البرج، الأسدان، 6 أشعة، سطرا النص).
- `tools/template.html` — كود الموشن قبل تضمين الصور. كل التوقيتات في الكائن `TL` أعلى السكريبت.
- `tools/meta.json` — مواقع الطبقات، مركز القبة ونصف قطرها، ونقاط بداية ونهاية كل شعاع.
- `tools/1_segment_layers.py` — يفكك الشعار الأصلي إلى طبقات (OpenCV). ضع الشعار باسم `src.jpg` بجانبه وشغّله.
- `tools/2_build_html.py` — يعيد بناء `oran-motion.html` بعد تعديل `template.html` أو الطبقات.
- `tools/3_render_video.py` — يصدّر MP4 إطاراً بإطار: `python3 3_render_video.py 1920 1080 out.mp4`

## المتطلبات (للسكريبتات فقط)
```
pip install opencv-python numpy pillow playwright
playwright install chromium
```
وبرنامج ffmpeg مثبت في النظام.

## تعديل الحركة
في `template.html` غيّر قيم `TL` (بالثواني) ثم شغّل `2_build_html.py`. أمثلة:
- `lionL: [2.3, 3.05]` بداية ونهاية دخول الأسد الأيسر.
- `flood: [3.85, 4.85]` تحوّل الخلفية إلى البياض.
- `text1` و `text2` كتابة السطرين.
الدالة `render(ctx, W, H, t)` ترسم أي لحظة `t` بشكل حتمي، لذلك التصدير مطابق للمعاينة.
