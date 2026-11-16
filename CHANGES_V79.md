# V79 – كل التعديلات

1. DuelScoreboardManager: إصلاح خطأ البناء (Arena.getDisplayName -> getId).
2. RoundPullManager (مشهد الراوندات):
   - حركة ناعمة بدون اهتزاز (سرعة من المسار + تصحيح خفيف 10%).
   - جزيئات أخف (حلقات أقل).
   - تدوير الكاميرا مرة عند البداية ومرة عند التواجه فقط (بدل كل tick).
   - سقف واطي: بدون صعود، ونقل للسبون عند النزول.
   - كشف العلوق: لو ما تحرك 0.5 ثانية ننقله للسبون.
3. FlySpectatorListener (المتفرج):
   - تصفير دفعة الـ velocity بدل إلغائها.
   - تصفير الحركة بعد الانفجار/الويند تشارج مع إرجاعه لمكانه لو انزاح أكثر من 3 بلوكات (احتياط لو PacketEvents غير موجود).
   - البلوكات: السيرفر يتجاهل المتفرج عند البناء (BlockCanBuildEvent).
   - الكريستال/الأرمور ستاند: ينقل المتفرج فوق المنطقة لو داخلها فقط.
4. ExplosionPacketGuard + PacketEvents (softdepend): تصفير دفعة الانفجار داخل الباكيت للمتفرجين، reflection + رجوع تلقائي للاحتياط لو فشل.
   - pom.xml: مكتبة packetevents-spigot 2.8.0 (provided) + مستودع codemc-releases.
   - plugin.yml: softdepend يشمل packetevents.
5. FlySpectatorListener: الكريستال فوق الأوبسيديان/البدروك — لو العائق متفرج فقط، نحط الكريستال بأنفسنا (بدون نقل المتفرج). أي كيان ثاني يسد المكان يبقى الرفض الطبيعي.

# V92 – نظام التيرات
راجع TIER_SYSTEM.md. جديد: com.duel.tier.*, /tier, matchmaking بالتقييم، Rankup Challenge، Tier Defense، مواسم، placeholders.
