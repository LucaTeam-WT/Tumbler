package com.phucho0237.lucaauth.data.db

/** Non-secret columns of [AccountEntity], read by [AccountDao.observeAll]. */
data class AccountSummaryRow(
    val id: String,
    val issuer: String,
    val accountName: String,
    val type: String,
    val algorithm: String,
    val digits: Int,
    val period: Int,
    val counter: Long,
)
