package com.suishiji.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [Transaction::class, Category::class, FixedExpense::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun fixedExpenseDao(): FixedExpenseDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN keywords TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN createdAt INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `fixed_expenses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayOfMonth` INTEGER NOT NULL, `amount` REAL NOT NULL, `category` TEXT NOT NULL, `note` TEXT NOT NULL DEFAULT '')")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 只更新仍为默认关键词的分类，用户自定义过的不会被覆盖
                db.execSQL("UPDATE categories SET keywords = '美团,饿了么,肯德基,麦当劳,星巴克,奶茶,火锅,餐厅,外卖,小吃,咖啡,面包,蛋糕,木桶饭,黄焖鸡,螺蛳粉,麻辣烫,沙县,兰州拉面,面馆,快餐,便当,食堂,饭馆,饺子,馄饨,烧烤,串串,炸鸡,汉堡,披萨,寿司,日料,料理,水果,饮品,茶饮,果汁,冰淇淋,甜品,烘焙,零食,买菜,生鲜,早餐,午餐,晚餐,夜宵,米线,米粉,酸辣粉,牛肉面,卤味,鸭脖' WHERE name = '食物' AND keywords = '美团,饿了么,肯德基,麦当劳,星巴克,奶茶,火锅,餐厅,外卖,小吃,咖啡,面包,蛋糕'")
                db.execSQL("UPDATE categories SET keywords = '淘宝,京东,拼多多,超市,便利,药店,日用,百货,快递,美团优选,多多买菜,叮咚买菜,盒马,山姆,Costco,屈臣氏,名创优品,无印良品,宜家,家居,日用品,洗护,化妆品,美妆,文具,书店,图书,母婴,宠物,鲜花,眼镜,维修,干洗,理发,美发,美容,洗浴,杂货,五金,配镜' WHERE name = '生活' AND keywords = '淘宝,京东,拼多多,超市,便利,药店,日用,百货,快递'")
                db.execSQL("UPDATE categories SET keywords = '电影,KTV,游戏,Steam,会员,视频,音乐,爱奇艺,腾讯视频,网易云,哔哩哔哩,B站,bilibili,优酷,芒果TV,QQ音乐,酷狗,猫眼,淘票票,游乐园,景点,门票,旅游,度假,健身,运动,羽毛球,游泳,剧本杀,密室,桌游,棋牌,网咖,网吧,电竞,直播,打赏,订阅,小说,漫画' WHERE name = '娱乐' AND keywords = '电影,KTV,游戏,Steam,会员,视频,音乐,爱奇艺,腾讯视频,网易云'")
                db.execSQL("UPDATE categories SET keywords = '滴滴,高铁,机票,加油,停车,地铁,公交,打车,出租,高速,ETC,骑行,哈啰,青桔,共享单车,火车票,动车,飞机,航班,顺风车,专车,快车,出租车,租车,代驾,驾校,学车,过路费,路桥,充电桩,充电,燃油,加油卡,轮胎,保养,修车' WHERE name = '交通' AND keywords = '滴滴,高铁,机票,加油,停车,地铁,公交,打车,出租,高速,ETC,骑行'")
                db.execSQL("UPDATE categories SET keywords = '酒店,民宿,房租,物业,airbnb,旅馆,公寓,如家,汉庭,全季,希尔顿,万豪,洲际,日租,月租,水电,水费,电费,燃气,天然气,暖气,网费,宽带' WHERE name = '住宿' AND keywords = '酒店,民宿,房租,物业,airbnb,旅馆,公寓'")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE fixed_expenses ADD COLUMN skippedMonths TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "suishiji.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            INSTANCE?.let { seedCategories(it.categoryDao()) }
                        }
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }

        private suspend fun seedCategories(dao: CategoryDao) {
            if (dao.count() > 0) return
            val defaults = listOf(
                Category(name = "食物", icon = "restaurant", isIncome = false,
                    keywords = "美团,饿了么,肯德基,麦当劳,星巴克,奶茶,火锅,餐厅,外卖,小吃,咖啡,面包,蛋糕,木桶饭,黄焖鸡,螺蛳粉,麻辣烫,沙县,兰州拉面,面馆,快餐,便当,食堂,饭馆,饺子,馄饨,烧烤,串串,炸鸡,汉堡,披萨,寿司,日料,料理,水果,饮品,茶饮,果汁,冰淇淋,甜品,烘焙,零食,买菜,生鲜,早餐,午餐,晚餐,夜宵,米线,米粉,酸辣粉,牛肉面,卤味,鸭脖"),
                Category(name = "生活", icon = "shopping_bag", isIncome = false,
                    keywords = "淘宝,京东,拼多多,超市,便利,药店,日用,百货,快递,美团优选,多多买菜,叮咚买菜,盒马,山姆,Costco,屈臣氏,名创优品,无印良品,宜家,家居,日用品,洗护,化妆品,美妆,文具,书店,图书,母婴,宠物,鲜花,眼镜,维修,干洗,理发,美发,美容,洗浴,杂货,五金,配镜"),
                Category(name = "娱乐", icon = "sports_esports", isIncome = false,
                    keywords = "电影,KTV,游戏,Steam,会员,视频,音乐,爱奇艺,腾讯视频,网易云,哔哩哔哩,B站,bilibili,优酷,芒果TV,QQ音乐,酷狗,猫眼,淘票票,游乐园,景点,门票,旅游,度假,健身,运动,羽毛球,游泳,剧本杀,密室,桌游,棋牌,网咖,网吧,电竞,直播,打赏,订阅,小说,漫画"),
                Category(name = "交通", icon = "directions_car", isIncome = false,
                    keywords = "滴滴,高铁,机票,加油,停车,地铁,公交,打车,出租,高速,ETC,骑行,哈啰,青桔,共享单车,火车票,动车,飞机,航班,顺风车,专车,快车,出租车,租车,代驾,驾校,学车,过路费,路桥,充电桩,充电,燃油,加油卡,轮胎,保养,修车"),
                Category(name = "住宿", icon = "home", isIncome = false,
                    keywords = "酒店,民宿,房租,物业,airbnb,旅馆,公寓,如家,汉庭,全季,希尔顿,万豪,洲际,日租,月租,水电,水费,电费,燃气,天然气,暖气,网费,宽带"),
                Category(name = "其它", icon = "more_horiz", isIncome = false, keywords = ""),
                Category(name = "工资", icon = "account_balance", isIncome = true, keywords = "工资,薪资,月薪"),
                Category(name = "兼职", icon = "work", isIncome = true, keywords = "兼职,外快,副业"),
                Category(name = "理财", icon = "trending_up", isIncome = true, keywords = "理财,利息,分红,基金,股票"),
                Category(name = "其它收入", icon = "attach_money", isIncome = true, keywords = ""),
            )
            for (cat in defaults) { dao.insert(cat) }
        }
    }
}
