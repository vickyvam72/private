package com.lumisignal.idxscreener.engine

import com.lumisignal.idxscreener.model.Candle
import com.lumisignal.idxscreener.model.SignalStatus
import com.lumisignal.idxscreener.model.TradeTick
import org.junit.Assert.*
import org.junit.Test

class AuditAndPerformanceTest {
    private fun candle(t:Long,low:Double,high:Double,close:Double=(low+high)/2,open:Double=close)=Candle(t,open,high,low,close,null,1_000)

    @Test fun waitingThenHoldingThenTp() {
        val waiting=AuditEngine.evaluate(1000.0,1020.0,1100.0,960.0,SignalStatus.WAITING_ENTRY,listOf(candle(1,1030.0,1060.0)),10)
        assertEquals(SignalStatus.WAITING_ENTRY,waiting.status)
        val holding=AuditEngine.evaluate(1000.0,1020.0,1100.0,960.0,SignalStatus.WAITING_ENTRY,listOf(candle(1,1010.0,1050.0)),10)
        assertEquals(SignalStatus.HOLDING,holding.status)
        val tp=AuditEngine.evaluate(1000.0,1020.0,1100.0,960.0,SignalStatus.HOLDING,listOf(candle(2,1030.0,1110.0)),10)
        assertEquals(SignalStatus.TAKE_PROFIT,tp.status)
    }

    @Test fun sameCandleTpSlIsAmbiguous() {
        val result=AuditEngine.evaluate(1000.0,1020.0,1100.0,960.0,SignalStatus.HOLDING,listOf(candle(1,950.0,1110.0)),10)
        assertEquals(SignalStatus.AMBIGUOUS,result.status)
    }

    @Test fun winRateExcludesOpenAndAmbiguous() {
        val rows=(1..7).map{SignalStatus.TAKE_PROFIT to 5.0}+(1..3).map{SignalStatus.STOP_LOSS to -3.0}+listOf(SignalStatus.HOLDING to null,SignalStatus.WAITING_ENTRY to null,SignalStatus.AMBIGUOUS to null)
        val p=PerformanceEngine.calculate(rows)
        assertEquals(70.0,p.winRate,0.0001)
        assertEquals(10,p.finished)
    }

    @Test fun auditIsIdempotentForTerminalStatus() {
        val result=AuditEngine.evaluate(1000.0,1020.0,1100.0,960.0,SignalStatus.TAKE_PROFIT,listOf(candle(2,900.0,1200.0)),10)
        assertEquals(SignalStatus.TAKE_PROFIT,result.status)
        assertNull(result.exitEpoch)
    }

    @Test fun liveIntradayTouchTriggersEntryWithoutUsingBarCountAsExpiry() {
        val completed=listOf(candle(1,1030.0,1060.0),candle(2,1025.0,1050.0))
        val live=(10L..35L).map { candle(it,if(it==28L)995.0 else 1025.0,if(it==28L)1010.0 else 1040.0) }
        val result=AuditEngine.evaluateCompletedThenLive(1000.0,1020.0,1100.0,960.0,SignalStatus.WAITING_ENTRY,completed,live,10)
        assertEquals(SignalStatus.HOLDING,result.status)
        assertEquals(28L,result.entryEpoch)
    }

    @Test fun entryUsesOpenWhenOpenIsInsideRange() {
        val result=AuditEngine.evaluate(100.0,105.0,115.0,95.0,SignalStatus.WAITING_ENTRY,listOf(candle(1,100.0,105.0,open=101.0)),10)
        assertEquals(SignalStatus.HOLDING,result.status)
        assertEquals(101.0,result.entryPrice!!,0.0)
        assertTrue(IDXTickSizeEngine.isValid(result.entryPrice!!.toInt()))
    }

    @Test fun entryUsesRangeBoundaryWhenOpenIsBelowRange() {
        val result=AuditEngine.evaluate(100.0,105.0,115.0,95.0,SignalStatus.WAITING_ENTRY,listOf(candle(1,98.0,101.0,open=99.0)),10)
        assertEquals(SignalStatus.HOLDING,result.status)
        assertEquals(100.0,result.entryPrice!!,0.0)
    }

    @Test fun entryIsRoundedToValidIdxFraction() {
        val result=AuditEngine.evaluate(1000.0,1020.0,1100.0,960.0,SignalStatus.WAITING_ENTRY,listOf(candle(1,1000.0,1020.0,open=1007.0)),10)
        assertEquals(1005.0,result.entryPrice!!,0.0)
        assertTrue(IDXTickSizeEngine.isValid(result.entryPrice!!.toInt()))
    }

    @Test fun runningTradeOrderResolvesEntryThenTakeProfit() {
        val ticks = listOf(
            TradeTick(3, 1_110.0), // payload may arrive newest first
            TradeTick(1, 1_010.0),
            TradeTick(2, 1_040.0)
        )
        val result = AuditEngine.evaluateTicks(
            1_000.0, 1_020.0, 1_100.0, 960.0,
            SignalStatus.WAITING_ENTRY, ticks
        )
        assertEquals(SignalStatus.TAKE_PROFIT, result.status)
        assertEquals(1L, result.entryEpoch)
        assertEquals(3L, result.exitEpoch)
    }

    @Test fun runningTradeOrderResolvesStopBeforeLaterTakeProfit() {
        val ticks = listOf(
            TradeTick(10, 1_010.0),
            TradeTick(11, 955.0),
            TradeTick(12, 1_110.0)
        )
        val result = AuditEngine.evaluateTicks(
            1_000.0, 1_020.0, 1_100.0, 960.0,
            SignalStatus.WAITING_ENTRY, ticks
        )
        assertEquals(SignalStatus.STOP_LOSS, result.status)
        assertEquals(11L, result.exitEpoch)
    }
}
