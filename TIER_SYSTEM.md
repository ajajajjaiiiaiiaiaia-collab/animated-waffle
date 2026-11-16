# نظام التيرات (MCPvP-style) — V92

لكل أرينا (kit) تير وتقييم مستقل. يشتغل على مباريات **الكيو** فقط (نفس شرط التوبات).

## الفكرة
1. كل لاعب يبدأ **LT5** بتقييم **1000** بكل أرينا.
2. فوز/خسارة بالكيو يحرّك التقييم (ELO، K=32، وأول 3 مباريات K=40).
3. لما التقييم يوصل عتبة التير التالي → **Rankup Challenge**: مباراتك الجاية بالكيو بنفس الأرينا تصير best-of-3 راوندات. الفوز = ترقية.
   الخسارة = cooldown 30 دقيقة قبل ما ينفتح لك تحدي ثاني.
4. لو نزل تقييمك تحت عتبة تيرك بـ60 نقطة → **Tier Defense**: مباراتك الجاية ضد لاعب بنفس التير. الخسارة = تنزل تير.
5. **مواسم**: `/tier admin season end` يأرشف كل شي ويسوي soft reset (يحتفظ بنص الفرق عن 1000)، والـ Peak tier يبقى.
   **Hall of Legends** = أفضل 5 من أرشيف الموسم السابق.

## Matchmaking
مدى ±100 يتوسع 10 كل ثانية انتظار (حد أقصى 600)، وبعد 90 ثانية أي خصم. التحدي يدور خصم تيره >= تيرك، والدفاع يدور نفس التير
(الشرط يتخفف بعد 45 ثانية). ticker كل ثانية يزاوج المنتظرين لما المدى يتوسع.

## الحماية من الاستغلال
- نفس الـ IP = ما يحسب.
- بعد 3 مباريات بالساعة ضد نفس اللاعب = ما يغيّر التقييم.
- مباراة أقصر من 10 ثواني (غير الرواندات) = ما تحسب.

## الأوامر
- `/tier` قائمة تيراتك — `/tier info [arena]` — `/tier top [arena]` — `/tier hall [arena]` — `/tier list`
- أدمن: `/tier admin season end` | `set <player> <arena> <tier>` | `rating <player> <arena> <n>` | `reset <player> [arena]`

## PlaceholderAPI
`%duel_tier_best%` `%duel_tier_current%` `%duel_tier_<arena>%` (+ `_plain` `_rating` `_peak` `_challenge` `_defense`)
`%duel_tier_top_<arena>_<rank>%` (+ `_score` `_tier`) — الألوان بصيغة & (للـ TAB).

## الإعدادات
كل شي تحت `tier:` في config.yml (العتبات، K، الـ cooldown، المدى، الحماية، المواسم).
جداول القاعدة الجديدة: `tier_profiles`, `tier_history`, `tier_meta` (تنشأ تلقائياً، ما تمس الجداول القديمة).

## الملفات
`com/duel/tier/` (Tier, TierProfile, TierManager, TierGUI) + `commands/TierCommand`.
تعديلات صغيرة بـ: DuelPlugin, DatabaseManager, DuelManager (hook نهاية المباراة), QueueManager (matchmaking + ticker),
InventoryListener (التحدي + handler), DuelPlaceholders, config.yml, plugin.yml.

## أيقونة الكيت بالتاب (V93)
`%duel_tier_icon%` = أيقونة كيت أعلى تير + التير (مثل [سيف] HT1) — `%duel_tier_icon_<arena>%` — `%duel_tier_current_icon%`.
الأيقونة = أول أيتم في GUI الأرينا، أو تحددها يدوياً في `tier.tab-icon.overrides`. تحتاج عميل 1.21.9+.
