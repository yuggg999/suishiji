package com.suishiji.db

import androidx.room.*

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(transaction: Transaction): Long

    @Delete
    suspend fun delete(transaction: Transaction)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): Transaction?

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE transactions SET category = :category WHERE id IN (:ids)")
    suspend fun updateCategoryByIds(ids: List<Long>, category: String)

    @Query("SELECT * FROM transactions WHERE date >= :start AND date < :end ORDER BY createdAt DESC, id DESC")
    suspend fun getBetween(start: Long, end: Long): List<Transaction>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'expense' AND date >= :start AND date < :end")
    suspend fun getExpenseSum(start: Long, end: Long): Double

    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE type = 'income' AND date >= :start AND date < :end")
    suspend fun getIncomeSum(start: Long, end: Long): Double

    @Query("SELECT DISTINCT date FROM transactions WHERE date >= :start AND date < :end")
    suspend fun getDatesWithTransactions(start: Long, end: Long): List<Long>

    @Query("SELECT category, SUM(amount) as total FROM transactions WHERE type = :type AND date >= :start AND date < :end GROUP BY category ORDER BY total DESC")
    suspend fun getCategorySums(type: String, start: Long, end: Long): List<CategorySum>

    @Query("SELECT * FROM transactions WHERE latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY createdAt DESC")
    suspend fun getWithLocation(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE latitude IS NOT NULL AND longitude IS NOT NULL AND date >= :start AND date < :end ORDER BY createdAt DESC")
    suspend fun getWithLocationBetween(start: Long, end: Long): List<Transaction>

    @Query("UPDATE transactions SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String)

    @Query("UPDATE transactions SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String)

    @Query("UPDATE transactions SET latitude = :lat, longitude = :lon, address = :addr WHERE id = :id")
    suspend fun updateLocation(id: Long, lat: Double?, lon: Double?, addr: String?)

    @Query("UPDATE transactions SET amount = :amount, type = :type, category = :category, note = :note, date = :date, latitude = :lat, longitude = :lon, address = :addr WHERE id = :id")
    suspend fun updateById(id: Long, amount: Double, type: String, category: String, note: String, date: Long, lat: Double?, lon: Double?, addr: String?)

    @Query("SELECT * FROM transactions ORDER BY createdAt DESC")
    suspend fun getAll(): List<Transaction>

    @Insert
    suspend fun insertAll(transactions: List<Transaction>)

    @Query("SELECT * FROM transactions WHERE type = 'expense' AND amount = :amount AND note LIKE '%' || :source || '%' ORDER BY date DESC LIMIT 1")
    suspend fun findExpenseByAmount(amount: Double, source: String): Transaction?

    @Query("SELECT date, SUM(amount) as total FROM transactions WHERE type = 'expense' AND date >= :start AND date < :end GROUP BY date")
    suspend fun getDailyExpenseSums(start: Long, end: Long): List<DailyExpenseSum>
}

data class DailyExpenseSum(
    val date: Long,
    val total: Double
)

data class CategorySum(
    val category: String,
    val total: Double
)

@Dao
interface CategoryDao {
    @Insert
    suspend fun insert(category: Category): Long

    @Query("SELECT * FROM categories WHERE isIncome = :isIncome ORDER BY id")
    suspend fun getByType(isIncome: Boolean): List<Category>

    @Query("SELECT * FROM categories ORDER BY id")
    suspend fun getAll(): List<Category>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT * FROM categories WHERE keywords != '' AND :text LIKE '%' || keywords || '%'")
    suspend fun findByKeyword(text: String): List<Category>

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)
}

@Dao
interface FixedExpenseDao {
    @Insert
    suspend fun insert(fixedExpense: FixedExpense): Long

    @Query("SELECT * FROM fixed_expenses ORDER BY dayOfMonth")
    suspend fun getAll(): List<FixedExpense>

    @Update
    suspend fun update(fixedExpense: FixedExpense)

    @Delete
    suspend fun delete(fixedExpense: FixedExpense)

    @Query("DELETE FROM fixed_expenses WHERE id = :id")
    suspend fun deleteById(id: Long)
}
