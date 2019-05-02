#include <vector>

std::vector<uint8_t> LoadImageFromFile(const char* file_name,
						 int* out_width,
						 int* out_height,
						 int* out_channels);

std::vector<uint8_t> LoadImageFromFileResize(const char* file_name,
                                             int* out_width, int* out_height,
                                             int* out_channels, int new_width, int new_height);

NSData *CompressImage(NSMutableData*,
						 int width,
						 int height,
             int bytesPerPixel);

