/** Leaving the website (payment page) and other full-page moves; replaced in tests. */
export const page = {
  open: (url: string) => void window.location.assign(url),
};
