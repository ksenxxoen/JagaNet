//! The client end of a Windows named pipe, opened with `FILE_FLAG_OVERLAPPED`.
//!
//! Why not `std::fs::OpenOptions`: that opens the pipe synchronously, and Windows serializes
//! I/O on a synchronous file object. A reader parked in `ReadFile` (it waits for the host's next
//! line) then blocks every `WriteFile` on the same object, even through a `try_clone()`d handle,
//! so a request could never be sent while the reader waited and the window stayed empty
//! (issue #106). With an overlapped handle each read and write is its own operation with its own
//! `OVERLAPPED` and event, and they run side by side. `PipeReader` and `PipeWriter` share the
//! handle; each call waits only for its own operation.

use std::ffi::OsStr;
use std::io::{self, Read, Write};
use std::os::windows::ffi::OsStrExt;
use std::sync::Arc;
use windows_sys::Win32::Foundation::{
    CloseHandle, GetLastError, ERROR_BROKEN_PIPE, ERROR_HANDLE_EOF, ERROR_IO_PENDING, ERROR_NO_DATA,
    ERROR_PIPE_NOT_CONNECTED, GENERIC_READ, GENERIC_WRITE, HANDLE, INVALID_HANDLE_VALUE,
};
use windows_sys::Win32::Storage::FileSystem::{CreateFileW, ReadFile, WriteFile, FILE_FLAG_OVERLAPPED, OPEN_EXISTING};
use windows_sys::Win32::System::Threading::CreateEventW;
use windows_sys::Win32::System::IO::{GetOverlappedResult, OVERLAPPED};

struct Handle(HANDLE);
// A pipe handle may be used from any thread; the overlapped operations are independent.
unsafe impl Send for Handle {}
unsafe impl Sync for Handle {}
impl Drop for Handle {
    fn drop(&mut self) {
        unsafe { CloseHandle(self.0) };
    }
}

/// An event for one operation at a time (manual reset, as `GetOverlappedResult` expects).
struct Event(HANDLE);
unsafe impl Send for Event {}
impl Event {
    fn new() -> io::Result<Self> {
        let h = unsafe { CreateEventW(std::ptr::null(), 1, 0, std::ptr::null()) };
        if h.is_null() {
            return Err(io::Error::last_os_error());
        }
        Ok(Event(h))
    }
}
impl Drop for Event {
    fn drop(&mut self) {
        unsafe { CloseHandle(self.0) };
    }
}

pub struct PipeReader {
    h: Arc<Handle>,
    ev: Event,
}
pub struct PipeWriter {
    h: Arc<Handle>,
    ev: Event,
}

/// Opens `\\.\pipe\<name>` for reading and writing, overlapped. Fails like `File::open` while
/// the host has not created the pipe yet (the caller retries).
pub fn open(pipe: &str) -> io::Result<(PipeReader, PipeWriter)> {
    let wide: Vec<u16> = OsStr::new(pipe).encode_wide().chain(std::iter::once(0)).collect();
    let h = unsafe {
        CreateFileW(wide.as_ptr(), GENERIC_READ | GENERIC_WRITE, 0, std::ptr::null(), OPEN_EXISTING, FILE_FLAG_OVERLAPPED, std::ptr::null_mut())
    };
    if h == INVALID_HANDLE_VALUE {
        return Err(io::Error::last_os_error());
    }
    let h = Arc::new(Handle(h));
    Ok((PipeReader { h: h.clone(), ev: Event::new()? }, PipeWriter { h, ev: Event::new()? }))
}

/// Waits for the operation started with `ov` and returns the bytes it moved.
fn finish(h: &Handle, ov: &mut OVERLAPPED, started: bool, err: u32) -> io::Result<usize> {
    if !started && err != ERROR_IO_PENDING {
        return Err(io::Error::from_raw_os_error(err as i32));
    }
    let mut n = 0u32;
    if unsafe { GetOverlappedResult(h.0, ov, &mut n, 1) } == 0 {
        return Err(io::Error::from_raw_os_error(unsafe { GetLastError() } as i32));
    }
    Ok(n as usize)
}

impl Read for PipeReader {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        let mut ov: OVERLAPPED = unsafe { std::mem::zeroed() };
        ov.hEvent = self.ev.0;
        let len = buf.len().min(u32::MAX as usize) as u32;
        let ok = unsafe { ReadFile(self.h.0, buf.as_mut_ptr().cast(), len, std::ptr::null_mut(), &mut ov) } != 0;
        let err = unsafe { GetLastError() };
        match finish(&self.h, &mut ov, ok, err) {
            Ok(n) => Ok(n),
            // The host hung up: end of file, as for any reader.
            Err(e) if matches!(e.raw_os_error().map(|c| c as u32), Some(ERROR_BROKEN_PIPE | ERROR_HANDLE_EOF | ERROR_PIPE_NOT_CONNECTED | ERROR_NO_DATA)) => Ok(0),
            Err(e) => Err(e),
        }
    }
}

impl Write for PipeWriter {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        let mut ov: OVERLAPPED = unsafe { std::mem::zeroed() };
        ov.hEvent = self.ev.0;
        let len = buf.len().min(u32::MAX as usize) as u32;
        let ok = unsafe { WriteFile(self.h.0, buf.as_ptr().cast(), len, std::ptr::null_mut(), &mut ov) } != 0;
        let err = unsafe { GetLastError() };
        finish(&self.h, &mut ov, ok, err)
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};
    use windows_sys::Win32::Storage::FileSystem::{ReadFile as SyncRead, WriteFile as SyncWrite, PIPE_ACCESS_DUPLEX};
    use windows_sys::Win32::System::Pipes::{ConnectNamedPipe, CreateNamedPipeW, PIPE_TYPE_BYTE, PIPE_WAIT};

    /// The bug of #106 in miniature: while a read waits for data, a write on the same pipe
    /// must still go through. With a synchronous handle this test hangs.
    #[test]
    fn write_goes_through_while_a_read_is_pending() {
        let name = format!(r"\\.\pipe\BoopApp-test-{}", std::process::id());
        let wide: Vec<u16> = OsStr::new(&name).encode_wide().chain(std::iter::once(0)).collect();
        let server = unsafe { CreateNamedPipeW(wide.as_ptr(), PIPE_ACCESS_DUPLEX, PIPE_TYPE_BYTE | PIPE_WAIT, 1, 4096, 4096, 0, std::ptr::null()) };
        assert_ne!(server, INVALID_HANDLE_VALUE);
        let server = Handle(server);
        let (mut r, mut w) = open(&name).unwrap();
        unsafe { ConnectNamedPipe(server.0, std::ptr::null_mut()) };
        let server = Arc::new(server);
        let s2 = server.clone();
        // The server echoes what it gets, but only after the client's read has been waiting a while.
        let echo = std::thread::spawn(move || {
            let mut buf = [0u8; 64];
            let mut n = 0u32;
            unsafe { SyncRead(s2.0, buf.as_mut_ptr().cast(), 64, &mut n, std::ptr::null_mut()) };
            std::thread::sleep(Duration::from_millis(300));
            let mut m = 0u32;
            unsafe { SyncWrite(s2.0, buf.as_ptr().cast(), n, &mut m, std::ptr::null_mut()) };
        });
        let reader = std::thread::spawn(move || {
            let mut buf = [0u8; 64];
            let n = r.read(&mut buf).unwrap();
            buf[..n].to_vec()
        });
        std::thread::sleep(Duration::from_millis(150)); // the read is parked now
        let t = Instant::now();
        w.write_all(b"ping\n").unwrap();
        assert!(t.elapsed() < Duration::from_secs(2), "the write waited for the read");
        assert_eq!(reader.join().unwrap(), b"ping\n");
        echo.join().unwrap();
    }
}
