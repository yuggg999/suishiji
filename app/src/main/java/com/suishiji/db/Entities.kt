package com.suishiji.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val type: String, // "expense" or "income"
    val category: String,
    val note: String = "",
    val date: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val isIncome: Boolean = false,
    val keywords: String = ""
)

@Entity(tableName = "fixed_expenses")
data class FixedExpense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayOfMonth: Int,
    val amount: Double,
    val category: String,
    val note: String = "",
    val skippedMonths: String = ""
)
