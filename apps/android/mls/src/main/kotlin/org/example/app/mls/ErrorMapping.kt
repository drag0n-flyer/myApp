package org.example.app.mls

import org.example.app.contracts.MlsException
import org.example.app.mls.ffi.FfiException

/** Maps the Rust engine's errors to [MlsException], one to one. */
internal object ErrorMapping {
    fun map(error: Throwable): MlsException = when (error) {
        is MlsException -> error
        is FfiException.Malformed -> MlsException.Malformed
        is FfiException.NotApplication -> MlsException.NotApplication
        is FfiException.UnknownGroup -> MlsException.UnknownGroup
        is FfiException.NotMember -> MlsException.NotMember
        is FfiException.Duplicate -> MlsException.Duplicate
        is FfiException.EpochGap -> MlsException.EpochGap
        is FfiException.PendingCommit -> MlsException.PendingCommit
        is FfiException.Storage -> MlsException.Storage(error.v1)
        is FfiException.Crypto -> MlsException.Crypto(error.v1)
        is FfiException.Internal -> MlsException.Internal(error.v1)
        // A Rust panic or loader failure: keep the type name only, never the message.
        else -> MlsException.Internal(error.javaClass.simpleName)
    }
}
