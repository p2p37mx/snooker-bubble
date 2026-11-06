# فقاعة الوصول 🛡️

فقاعة تطفو فوق التطبيقات: أول ما تشتغل تطفي كل تطبيقات إمكانية الوصول، ومنها تشغّل بس التطبيق اللي تريده.

- يحتاج صلاحية `WRITE_SECURE_SETTINGS` تنمنح مرة وحدة: `adb shell pm grant com.alwan.accessbubble android.permission.WRITE_SECURE_SETTINGS`
- البناء تلقائي على GitHub Actions، والـ APK يطلع بقسم Releases باسم «فقاعة الوصول».
- هذا الفرع (`access-bubble`) منفصل تماماً عن مساعد السنوكر (`main`).
