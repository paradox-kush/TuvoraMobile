package com.nuvio.app.core.telemetry.api

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** JDK / Android types by `is` check (most specific first); the strings are fixed here, never read from the class. */
internal actual fun platformErrorName(error: Throwable): String? = when (error) {
    is UnknownHostException -> "UnknownHostException"
    is SocketTimeoutException -> "SocketTimeoutException"
    is ConnectException -> "ConnectException"
    is NoRouteToHostException -> "NoRouteToHostException"
    is SocketException -> "SocketException"
    is SSLHandshakeException -> "SSLHandshakeException"
    is SSLException -> "SSLException"
    is EOFException -> "EOFException"
    is FileNotFoundException -> "FileNotFoundException"
    is ProtocolException -> "ProtocolException"
    is InterruptedIOException -> "InterruptedIOException"
    is TimeoutException -> "TimeoutException"
    is SQLiteFullException -> "SQLiteFullException"
    is SQLiteDiskIOException -> "SQLiteDiskIOException"
    is SQLiteDatabaseLockedException -> "SQLiteDatabaseLockedException"
    is SQLiteException -> "SQLiteException"
    is OutOfMemoryError -> "OutOfMemoryError"
    is StackOverflowError -> "StackOverflowError"
    else -> null
}
