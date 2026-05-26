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

@Database(entities = [Transaction::class, Category::class, FixedExpense::class], version = 4, exportSchema = false)
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "suishiji.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
                    keywords = "美团,饿了么,肯德基,麦当劳,星巴克,奶茶,火锅,餐厅,外卖,小吃,咖啡,面包,蛋糕"),
                Category(name = "生活", icon = "shopping_bag", isIncome = false,
                    keywords = "淘宝,京东,拼多多,超市,便利,药店,日用,百货,快递"),
                Category(name = "娱乐", icon = "sports_esports", isIncome = false,
                    keywords = "电影,KTV,游戏,Steam,会员,视频,音乐,爱奇艺,腾讯视频,网易云"),
                Category(name = "交通", icon = "directions_car", isIncome = false,
                    keywords = "滴滴,高铁,机票,加油,停车,地铁,公交,打车,出租,高速,ETC,骑行"),
                Category(name = "住宿", icon = "home", isIncome = false,
                    keywords = "酒店,民宿,房租,物业,airbnb,旅馆,公寓"),
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
